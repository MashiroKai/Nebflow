// panel-detail-route.spec.mjs — stream-919-unify 批核心回归（作者 2026-10-07
// 22:34 令＋10:10 澄清令，方向重下）。本文件由分层展示批（d78034635）的「双面
// 互斥」断言面整体重写为「统一回归 9.19」断言面：
//
//   作者原话（规格真源）：「我是要把当行流式改为跟 9.19 这个版本的显示方式。
//   不是把工具结果展开。然后思考过程要限制一个最大窗口进行流式，不要占满整个
//   屏幕。比如最多 5 行。」
//
// 即：d78034635 的「面板视图＝展开工具块」方向退役——主聊天流与子代理/flow
// 监视面板回归同一个 9.19 式统一面：
//   · 单行流式（workline）承载运行过程，类型化细节行两面都在（398dd72ac 保留
//     件，作者明示不在回退面）；
//   · 完成工具块 tucked（9.19 无展开块——面板与主行同观感），header toggle
//     真实有效（点击展开/收起同主行语义）；
//   · 历史重放同统一面（tucked + body 收起）；
//   · `.nf-panel` 路由标记退役（零消费者）——「路由收口点」不复存在，断言 =
//     面板视图构造后无标记、双容器同面；
//   · AskUserQuestion 问句细节行（作者 10-06 追加令保留件）两面同流。
//
// §③ 思考窗硬上限（同批新增硬规格）：思考过程内容窗最大 5 行（缺省常量
// THINKING_WINDOW_MAX_LINES=5），超出＝内部滚动（本批取舍申报：滚动形态，
// 内容零丢失）＋行数计数读数（data-nf-think-lines），绝不向下游无限撑开；
// 亮暗双主题同构（窗口长度非颜色，滚动条墨色双档镜像）。
//
// 保留件回归：398dd72ac 族（主行单行流式形态逐位）＋ 0584bf0bb（askuser-
// expandfix A1-A5 另件全绿，本 spec 不重复钉）。
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
const SHOT_DIR = path.join(REPO_ROOT, '.nebflow', 'evidence', '20261007_103042_stream-919-unify', 'spec');

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

async function newPage(browser, colorScheme = 'light') {
  const context = await browser.newContext({
    locale: 'zh-CN',
    colorScheme,
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

/** 统一面断言（9.19 观感，两面同式）：运行中细节行在、完成卡 tucked、body
 *  收起、终态 badge 收起过程行。 */
function expectUnifiedFace(r) {
  expect(r.running.linePresent).toBe(true);
  expect(r.running.itemKind).toBe('running');
  expect(r.running.detailPresent).toBe(true);
  expect(r.running.detailText).toBe('git status && npm test');
  expect(r.done.tools.length).toBe(1);
  expect(r.done.tools[0].tucked).toBe(true);
  expect(r.done.tools[0].hasBody).toBe(true);
  expect(r.done.tools[0].bodyOpenCls).toBe(false);
  expect(r.settled.headers.length).toBe(1);
  expect(r.settled.headers[0].state).toBe('done');
  expect(r.settled.tools[0].tucked).toBe(true);
  expect(r.settled.tools[0].visible).toBe(false);
}

test.describe('route retired (面板路由退役——9.19 无此面)', () => {
  test('no .nf-panel anywhere: panel view, main view and non-primary plain view are the SAME unified face', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    const probe = await page.evaluate(() => ({
      panel: window.__pdCensus('panel'),
      main: window.__pdCensus('main'),
      plain: window.__pdNewPlainView(),
    }));
    // d780 的 opt-in 标记退役：任何视图都不落 .nf-panel（构造器零 opts）。
    expect(probe.panel.marker).toBe(false);
    expect(probe.main.marker).toBe(false);
    // 非主视图（search-float 形态）同样零标记；UI-G 静窗类不受本批影响。
    expect(probe.plain.marker).toBe(false);
    expect(probe.plain.staticCls).toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('unified face: 9.19 观感两面同式 (面板＝主行)', () => {
  test('panel live flow: detail line streams, completed card tucked, terminal tucks — the 9.19 form', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__pdUse('panel'));
    const r = await runToolFlow(page, 'panel');
    expectUnifiedFace(r);
    const shot = path.join(SHOT_DIR, 'after-panel-unified.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('main live flow: the 398dd72ac single-line form unchanged (主行不回归)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__pdUse('main'));
    const r = await runToolFlow(page, 'main');
    expectUnifiedFace(r);
    const shot = path.join(SHOT_DIR, 'after-main-unified.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('unified identity: identical feed → the two containers render the SAME census (同源同观感，逐字段相等)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__pdUse('panel'));
    const panel = await runToolFlow(page, 'panel');

    await page.evaluate(() => window.__pdUse('main'));
    const main = await runToolFlow(page, 'main');

    // 两个独立容器（面板 Overlay 机制不动），但形态逐字段相等——统一面。
    // （比对用各自 toolFlow 的同拍 census 快照：running/done/settled 三拍逐字段。）
    const distinctContainers = await page.evaluate(() =>
      document.getElementById('panel-chat') !== document.getElementById('main-chat'));
    expect(distinctContainers).toBe(true);
    for (const beat of ['running', 'done', 'settled']) {
      expect(panel[beat].headers).toEqual(main[beat].headers);
      expect(panel[beat].tools).toEqual(main[beat].tools);
      expect(panel[beat].linePresent).toBe(main[beat].linePresent);
      expect(panel[beat].itemKind).toBe(main[beat].itemKind);
      expect(panel[beat].detailPresent).toBe(main[beat].detailPresent);
      expect(panel[beat].detailText).toBe(main[beat].detailText);
    }
    // 数据同源逐字一致（完成块命令/输出）。
    expect(main.done.tools[0].cmdText).toBe(panel.done.tools[0].cmdText);
    expect(main.done.tools[0].preText).toBe(panel.done.tools[0].preText);
    // 终态 = 单行 turn 线已同元素落成 badge（settleWorklineAsHeader morph 契约）。
    expect(main.settled.linePresent).toBe(false);
    expect(main.settled.headers.length).toBe(1);
    expect(main.settled.headers[0].state).toBe('done');
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('panel history restore: tucked rows + closed bodies + header (9.19 重放原貌)', async ({ browser }) => {
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
    expect(c.tools[0].tucked).toBe(true);
    expect(c.tools[0].visible).toBe(false);
    expect(c.tools[0].bodyOpenCls).toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('panel terminal toggle stays honest: click header untucks, click again tucks (与主行同语义)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__pdUse('panel'));
    await runToolFlow(page, 'panel');

    await page.evaluate(() => window.__pdClickHeader('panel')); // done → done-expanded（展开）
    let c = await page.evaluate(() => window.__pdCensus('panel'));
    expect(c.headers[0].state).toBe('done-expanded');
    expect(c.tools[0].tucked).toBe(false);
    expect(c.tools[0].visible).toBe(true);

    await page.evaluate(() => window.__pdClickHeader('panel')); // done-expanded → done（收起）
    c = await page.evaluate(() => window.__pdCensus('panel'));
    expect(c.headers[0].state).toBe('done');
    expect(c.tools[0].tucked).toBe(true);
    expect(c.tools[0].visible).toBe(false);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('AskUserQuestion 追加令保留件 (细节行两面同流)', () => {
  test('question streams into the detail line on BOTH faces (统一面同源一致)', async ({ browser }) => {
    for (const face of ['main', 'panel']) {
      const { context, page, pageErrors } = await newPage(browser);
      await page.evaluate(f => window.__pdUse(f), face);
      await page.evaluate(() => window.__pdUser('问个问题'));
      await page.evaluate(() => window.__pdCallStart('AskUserQuestion'));
      for (const chunk of ['{"question":"要继续部署', '到预发环境吗？"}']) {
        await page.evaluate(([n, c]) => window.__pdArg(n, c), ['AskUserQuestion', chunk]);
        await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r())));
      }
      const running = await page.evaluate(f => window.__pdCensus(f), face);
      // 细节行：问句随 LLM 生成逐字上屏（作者 10-06 裁决点名例；两面同式）。
      expect(running.detailPresent).toBe(true);
      expect(running.detailText).toBe('要继续部署到预发环境吗？');

      // 卡体流式面（TOOL_PRIMARY_FIELDS 同源行）：question 落 .tool-stream-body。
      const which = face === 'panel' ? 'panel' : 'main';
      const bodyFace = await page.evaluate(sel => {
        const el = document.querySelector(sel + ' .tool-stream-body pre');
        return el ? el.textContent : '';
      }, `#${which}-chat`);
      expect(bodyFace).toBe('要继续部署到预发环境吗？');
      expect(pageErrors).toEqual([]);
      await context.close();
    }
  });
});

test.describe('§③ 思考窗硬上限 (作者 10:10 澄清令·新增硬规格)', () => {
  const LINES = 12; // > 5 —— 溢出窗
  // 锚注（r2 重 derive，第二版）：思考正文经 markdown 渲染是软换行连续文本
  // （单 \n 不断行），「源行数 ⇒ 渲染高度」不成立——行数锚必须按**渲染行**
  // 构造，且对容器宽度稳健（第一版按「每源行 ≈1 渲染行」垫 12-18 字，在
  // 8 行喂线上恰骑在 5 渲染行边界（实测面板腿 scrollable=false），刀刃锚）。
  // r2 修锚：溢出腿每源行垫 40 个全角句点（≈47 字 ≈1.6 渲染行 @396px 窝宽），
  // N 行源文 ⇒ ≈1.6N 渲染行，任何 ≥250px 容器都确定溢出 5 行窗；不溢出腿
  // （within cap）保持短行（≈19 字 ≈0.6 渲染行 ⇒ 4 行 ≤2.5 渲染行 < 5）。
  const thinkLines = (n, fill = 40) => Array.from({ length: n }, (_, i) => `第${String(i + 1).padStart(2, '0')}行思考内容${'。'.repeat(fill)}`).join('\n');

  async function feedThinking(page, text) {
    await page.evaluate(() => window.__pdUser('想一想'));
    // 逐块喂（appendThinkingDelta 原生入口），每块一帧。
    await page.evaluate(t => window.__pdThink(t, { round: 0, block: 0 }), text);
    // 终态：finishThinking → 终态轮 → header 展开 → 思考 label 点开展开面。
    await page.evaluate(() => window.__pdThinkDone());
    await page.evaluate(() => window.__pdFinishTurn(900, 'test-model'));
    await page.evaluate(() => window.__pdClickHeader('main')); // untuck 过程行
    await page.evaluate(() => window.__pdThinkLabel('main')); // 展开 thinking 内容
  }

  test('overflow: window pinned at 5 lines, internal scroll, count reading, zero downstream blowout', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await feedThinking(page, thinkLines(LINES));
    const c = await page.evaluate(() => window.__pdCensus('main'));
    expect(c.think).not.toBeNull();
    const th = c.think;
    // 窗口恒 ≤ 5 行：声明上限 = 5 × 行高（±1px 取整容差），渲染窗不超过它。
    expect(th.lineHeightPx).toBeGreaterThan(0);
    expect(th.maxHeightPx).toBeCloseTo(th.lineHeightPx * 5, 0);
    expect(th.clientH).toBeLessThanOrEqual(th.maxHeightPx + 1);
    // 超出＝内部滚动。
    expect(th.overflowY).toBe('auto');
    expect(th.scrollable).toBe(true);
    expect(th.scrollH).toBeGreaterThan(th.clientH);
    // 计数读数 = 源文本行数。
    expect(th.count).toBe(String(LINES));
    // 下游布局零撑开：行高有界（窗 + label + 气泡 padding，远小于未设窗的
    // 12 行文本面），且聊天容器滚动高度小于未设窗时的量级（< 3 行窗高）。
    expect(th.rowH).toBeGreaterThan(0);
    expect(th.rowH).toBeLessThan(th.maxHeightPx + 80);
    // 下游零撑开（r2 锚修）：chat 列是 height:100% 的滚动容器，scrollHeight
    // 钳位在 clientHeight（内容总高 ≈400px < 1000px 视口）——「< 常数」形断言
    // 在 flex 列上几何不可满足（首版误锚，读数 1000=clientHeight）。诚实形态：
    // 窗封顶 ⇒ 列恒无溢出余量（scrollHeight == clientHeight）；尖锐窗界判据
    // 在上方 rowH 窗界腿（无窗回归形态 rowH ≈12 渲染行必炸）。
    const chatBox = await page.evaluate(() => {
      const el = document.getElementById('main-chat');
      return { sh: el.scrollHeight, ch: el.clientHeight };
    });
    expect(chatBox.sh).toBe(chatBox.ch);
    const shot = path.join(SHOT_DIR, 'after-think-window-cap.png');
    await page.screenshot({ path: shot, fullPage: true });
    expect(fs.existsSync(shot)).toBe(true);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('within cap: ≤5 lines → no scroll, count reading exact', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await feedThinking(page, thinkLines(4, 12)); // 短行（≈19 字 ≈0.6 渲染行/源行）⇒ 全文 < 5 渲染行
    const c = await page.evaluate(() => window.__pdCensus('main'));
    expect(c.think).not.toBeNull();
    expect(c.think.maxHeightPx).toBeCloseTo(c.think.lineHeightPx * 5, 0);
    expect(c.think.scrollable).toBe(false);
    expect(c.think.count).toBe('4');
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('panel face: same thinking-window contract (统一面——面板同上限)', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => window.__pdUse('panel'));
    await page.evaluate(() => window.__pdUser('想一想'));
    await page.evaluate(t => window.__pdThink(t, { round: 0, block: 0 }), thinkLines(8));
    await page.evaluate(() => window.__pdThinkDone());
    await page.evaluate(() => window.__pdFinishTurn(900, 'test-model'));
    await page.evaluate(() => window.__pdClickHeader('panel'));
    await page.evaluate(() => window.__pdThinkLabel('panel'));
    const c = await page.evaluate(() => window.__pdCensus('panel'));
    expect(c.think).not.toBeNull();
    expect(c.think.maxHeightPx).toBeCloseTo(c.think.lineHeightPx * 5, 0);
    expect(c.think.clientH).toBeLessThanOrEqual(c.think.maxHeightPx + 1);
    expect(c.think.scrollable).toBe(true);
    expect(c.think.count).toBe('8');
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('light/dark isomorphic: same window geometry in both schemes, scrollbar mirrored (双主题同构)', async ({ browser }) => {
    const read = async (scheme) => {
      const { context, page } = await newPage(browser, scheme);
      await page.evaluate(() => window.__pdUser('想一想'));
      await page.evaluate(t => window.__pdThink(t, { round: 0, block: 0 }), thinkLines(9));
      await page.evaluate(() => window.__pdThinkDone());
      await page.evaluate(() => window.__pdFinishTurn(900, 'test-model'));
      await page.evaluate(() => window.__pdClickHeader('main'));
      await page.evaluate(() => window.__pdThinkLabel('main'));
      const c = await page.evaluate(() => window.__pdCensus('main'));
      // CSSOM 暗档滚动条镜像规则在场。
      const darkRule = await page.evaluate(() => {
        for (const sheet of document.styleSheets) {
          let rules; try { rules = sheet.cssRules; } catch { continue; }
          for (const r of rules) {
            if (r.media && /dark/.test(r.media.mediaText) && r.cssText.includes('.thinking-content::-webkit-scrollbar-thumb')) return true;
          }
        }
        return false;
      });
      await context.close();
      return { geo: c.think, darkRule };
    };
    const light = await read('light');
    const dark = await read('dark');
    expect(dark.darkRule).toBe(true);
    // 窗口长度非颜色：双主题几何同值。
    expect(dark.geo.maxHeightPx).toBe(light.geo.maxHeightPx);
    expect(dark.geo.overflowY).toBe(light.geo.overflowY);
    expect(dark.geo.scrollable).toBe(true);
    expect(dark.geo.count).toBe(light.geo.count);
  });
});
