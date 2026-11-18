// ask-todo-lifecycle.spec.mjs — 溢出转待办批（2026-11-18 作者令）的**状态机闸**。
//
// 作者令（原）逐字：「超出对话分页的 AskUser 问题自动转入待办，不再弹窗和计数气泡显示，
// 但是用户往上翻消息的话还是可以通过原卡片作答，然后在待办和原卡片作答效果应该是
// 一样的，共同管理卡片的生命周期，不能出现待办已答但原卡还可以作答的不对称现象」。
// 作者令（第二轮）逐字：「ask转待办做的也不对 要参考我们最新的待办协议 支持像askuser
// 这样的问题卡回答了 所以转待办是转为具体的待办条目 而不是再待办上方显示问题」。
//
// 本 spec drives the REAL modules（askPending.js 判据单点 + todoStore.js 条目真源
// 适配器 + askTodoQuestion.js 条目内问题卡模板）through
// tests/fixtures/ask-todo-lifecycle/harness.html。
//
// 钉（原有四条 + 判据边界两条 + 并发四条 + 源码闸双向 + 新令三条）：
//   L1 溢出 ⇒ **转为同源待办条目**（id/name 可对账）+ 无弹窗无计数
//   L2 回到已加载页内 ⇒ 自动回 inline，条目不再显形（非单向闩锁）
//   L3 条目内作答 ⇒ 卡片锁定单点被调用 + 条目退场 + **再答 no-op**（幂等）
//   L3b 不对称态不可能：卡片后到（翻回去）也是已答锁定态
//   L4 反向：原卡先答 ⇒ 条目立刻消失
//   L5 竞态：同一问题两处几乎同时提交 ⇒ 先到者胜，只出 1 条回答帧
//   L6 无对话上下文（纯镜像夹具）⇒ 既有行为（inline），弹窗/徽标零回归
//   M1–M4 并发挂起：M1 并存 · M2 正序 · M3 反序 · M4 各自收口
//   G0 未改源码 ⇒ 绿
//   G1–G5 注入各必红、还原必绿（对话面收口 / 定位面交叉 / 帧单点 / 多槽镜像 / 未修源码）
//   G6 = (a) 破坏「条目身份映射」 ⇒ 红（A8）
//   G7 = (b) 把「列表上方 ask 专用区块」加回去 ⇒ 红（A9）
//   G8 = (c) 破坏「两侧同步收口」 ⇒ 红（A10）
//
// Run: npx playwright test tests/ask-todo-lifecycle.spec.mjs

import { test, expect } from '@playwright/test';
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS = '/tests/fixtures/ask-todo-lifecycle/harness.html';
const WEB = path.join(REPO_ROOT, 'src', 'main', 'resources', 'web');
const SHOT_DIR = process.env.NB_ASK_TODO_SHOT_DIR ||
  path.join(REPO_ROOT, '.nebflow', 'evidence', '20261118_ask-todo-lifecycle', 'spec');

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
  return spawn('python3', ['-m', 'http.server', String(p), '--bind', '127.0.0.1', '--directory', dir], { stdio: 'ignore' });
}

async function waitUntilUp(url, tries = 60) {
  for (let i = 0; i < tries; i++) {
    try { const res = await fetch(url); if (res.ok) return; } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error(`static server never came up at ${url}`);
}

test.beforeAll(async () => {
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  port = await freePort();
  expect(port).not.toBe(8080);
  expect(port).not.toBe(6138);
  servers.push(startStaticServer(port, REPO_ROOT));
  await waitUntilUp(`http://127.0.0.1:${port}${HARNESS}`);
});

test.afterAll(async () => {
  for (const s of servers) s.kill('SIGTERM');
  servers.length = 0;
});

async function newPage(browser) {
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 375, height: 812 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${port}${HARNESS}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

const ask = (rid, extra = {}) => ({
  requestId: rid,
  sessionId: 'harness-session',
  agentName: 'Nebula',
  items: [{ question: `问题 ${rid}?`, options: [{ label: '甲' }, { label: '乙' }] }],
  ...extra,
});

const settle = (page) => page.evaluate(() => window.__settle());

test.describe('溢出转待办 · 共同生命周期（2026-11-18 作者令）', () => {
  test('L1 超出对话分页 ⇒ 转为同源待办条目，且无弹窗无计数气泡', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    // 问题到达，但对话页窗口里**没有**它的卡片（超出分页）⇒ 判据应为 'todo'。
    await page.evaluate((m) => window.__note(m), ask('asknb-over-1'));
    await settle(page);

    expect(await page.evaluate(() => window.__placement('asknb-over-1'))).toBe('todo');
    // (a) 转出后**存在同源待办条目**：id / name 可对账（与 ask 请求一一对应）。
    expect(await page.evaluate(() => window.__entryIds())).toEqual([
      { requestId: 'asknb-over-1', id: 'task-asknb-over-1', source: 'ask', name: '[待答] 问题 asknb-over-1?' },
    ]);
    expect(await page.evaluate(() => window.__todoIds())).toEqual(['asknb-over-1']);
    expect(await page.evaluate(() => window.__todoCards())).toBe(1);
    // 弹窗条：零行；计数气泡：隐藏且 0（作者令「不再弹窗和计数气泡显示」）。
    expect(await page.evaluate(() => window.__barRows())).toBe(0);
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: false, count: '0' });
    // 镜像仍在（只是归属待办面）——条目面与对话面共用同一份状态。
    expect(await page.evaluate(() => window.__count())).toBe(1);
    expect(pageErrors).toEqual([]);
    await page.screenshot({ path: path.join(SHOT_DIR, 'l1-overflow-to-todo-entry.png') });
    await context.close();
  });

  test('L2 卡片回到已加载页内 ⇒ 自动回 inline，条目不再显形（非单向闩锁）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((m) => window.__note(m), ask('asknb-scroll-1'));
    await settle(page);
    expect(await page.evaluate(() => window.__todoCards())).toBe(1);

    // 用户往上翻 ⇒ 旧页被加载 ⇒ 卡片落在当前分页窗口内。
    await page.evaluate(() => window.__cardIn('asknb-scroll-1'));
    await settle(page);
    expect(await page.evaluate(() => window.__placement('asknb-scroll-1'))).toBe('inline');
    expect(await page.evaluate(() => window.__todoCards())).toBe(0);
    expect(await page.evaluate(() => window.__todoIds())).toEqual([]);
    expect(await page.evaluate(() => window.__barRows())).toBe(1);
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: true, count: '1' });

    // 再翻走（页窗口换掉）⇒ 条目重新显形。判定是投影，不是一次性迁移。
    await page.evaluate(() => window.__cardOut('asknb-scroll-1'));
    await settle(page);
    expect(await page.evaluate(() => window.__placement('asknb-scroll-1'))).toBe('todo');
    expect(await page.evaluate(() => window.__todoCards())).toBe(1);
    expect(await page.evaluate(() => window.__barRows())).toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('L3 条目内作答 ⇒ 卡片锁定单点被调用、条目退场、再答 no-op（幂等）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((m) => window.__note(m), ask('asknb-answer-1'));
    await settle(page);

    // 走真实 UI：点选项 + 点该条目问题卡的「提交回答」。
    expect(await page.evaluate(() => window.__todoPick('asknb-answer-1', '甲'))).toBe(true);
    expect(await page.evaluate(() => window.__todoSubmit('asknb-answer-1'))).toBe(true);
    await settle(page);

    const frames = await page.evaluate(() => window.__captured.filter(f => f.type === 'askUserAnswer'));
    expect(frames).toHaveLength(1);
    expect(frames[0]).toMatchObject({ type: 'askUserAnswer', sessionId: 'harness-session', requestId: 'asknb-answer-1', answers: ['甲'] });
    // 卡片锁定 = 引擎/本地共用的**单一收口**（closeAskUserCard）被调用一次。
    const closes = await page.evaluate(() => window.__closerCalls);
    expect(closes).toHaveLength(1);
    expect(closes[0].requestId).toBe('asknb-answer-1');
    // 已答 ⇒ 条目退场、弹窗/徽标仍为 0（不是换成「已答」再挂一条）。
    expect(await page.evaluate(() => window.__todoCards())).toBe(0);
    expect(await page.evaluate(() => window.__todoIds())).toEqual([]);
    expect(await page.evaluate(() => window.__barRows())).toBe(0);
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: false, count: '0' });

    // 幂等：同一问题再答（条目已无；直调入口同判）⇒ 不再产生第二条回答。
    const again = await page.evaluate(() => window.__submit({ requestId: 'asknb-answer-1', answers: ['乙'] }));
    expect(again).toEqual({ ok: false, reason: 'not-pending' });
    expect(await page.evaluate(() => window.__captured.filter(f => f.type === 'askUserAnswer'))).toHaveLength(1);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('L3c 条目侧取消 ⇒ 走同一收口（取消帧 + 锁定单点），两侧同步收口', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((m) => window.__note(m), ask('asknb-cancel-1'));
    await settle(page);
    expect(await page.evaluate(() => window.__todoCancel('asknb-cancel-1'))).toBe(true);
    await settle(page);
    const cancellations = await page.evaluate(() => window.__captured.filter(f => f.type === 'askUserCancel'));
    expect(cancellations).toHaveLength(1);
    expect(cancellations[0]).toMatchObject({ type: 'askUserCancel', requestId: 'asknb-cancel-1', by: 'web' });
    expect(await page.evaluate(() => window.__closerCalls.map(c => c.requestId))).toEqual(['asknb-cancel-1']);
    expect(await page.evaluate(() => window.__todoIds())).toEqual([]);
    expect(await page.evaluate(() => window.__placement('asknb-cancel-1'))).toBe(null);
    // 再取消 ⇒ no-op（幂等）。
    expect(await page.evaluate(() => window.__cancel('asknb-cancel-1'))).toEqual({ ok: false, reason: 'not-pending' });
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('L3b 不对称态不可能：卡片后到（翻回去）也是已答锁定态', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((m) => window.__note(m), ask('asknb-asym-1'));
    await settle(page);
    await page.evaluate(() => window.__todoPick('asknb-asym-1', '乙'));
    await page.evaluate(() => window.__todoSubmit('asknb-asym-1'));
    await settle(page);

    // 用户往上翻，原卡片进入页窗口：它必须落在已答锁定态，而不是可作答的活卡。
    await page.evaluate(() => window.__cardIn('asknb-asym-1'));
    await settle(page);
    expect(await page.evaluate(() => window.__cardState('asknb-asym-1'))).toBe('locked');
    // 该问题已不挂起 ⇒ 任何面都不再提供作答。
    expect(await page.evaluate(() => window.__placement('asknb-asym-1'))).toBe(null);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('L4 反向不对称同样不可能：原卡先答 ⇒ 条目立刻消失', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((m) => window.__note(m), ask('asknb-reverse-1'));
    await settle(page);
    expect(await page.evaluate(() => window.__todoCards())).toBe(1);
    // 卡上作答腿的本地收口（chat.js 卡内确认回调调用 removePendingAsk —— 同一单点）。
    await page.evaluate(() => window.__remove('asknb-reverse-1'));
    await settle(page);
    expect(await page.evaluate(() => window.__todoCards())).toBe(0);
    expect(await page.evaluate(() => window.__count())).toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('L5 竞态：两处几乎同时提交 ⇒ 先到者胜，只出一条回答帧（实测读数）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((m) => window.__note(m), ask('asknb-race-1'));
    await settle(page);

    const reading = await page.evaluate(() => {
      const t0 = performance.now();
      const a = window.__submit({ requestId: 'asknb-race-1', answers: ['甲'] });
      const b = window.__submit({ requestId: 'asknb-race-1', answers: ['乙'] });
      const frames = window.__captured.filter(f => f.type === 'askUserAnswer');
      return { first: a, second: b, frames: frames.length, answers: frames.map(f => f.answers), ms: performance.now() - t0 };
    });
    console.log('[L5 race reading]', JSON.stringify(reading));
    expect(reading.first.ok).toBe(true);
    expect(reading.second.ok).toBe(false);
    expect(['in-flight', 'not-pending']).toContain(reading.second.reason);
    expect(reading.frames).toBe(1);
    expect(reading.answers).toEqual([['甲']]);

    await settle(page);
    expect(await page.evaluate(() => window.__placement('asknb-race-1'))).toBe(null);
    expect(await page.evaluate(() => window.__todoCards())).toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('L6 无对话上下文（纯镜像夹具）⇒ 判据按既有行为（inline），弹窗/徽标零回归', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate(() => { document.getElementById('chat').remove(); });
    await page.evaluate((m) => window.__note(m), ask('asknb-legacy-1'));
    await settle(page);
    expect(await page.evaluate(() => window.__placement('asknb-legacy-1'))).toBe('inline');
    expect(await page.evaluate(() => window.__todoCards())).toBe(0);
    expect(await page.evaluate(() => window.__barRows())).toBe(1);
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: true, count: '1' });
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  // ── 作者追加令（2026-11-18 21:33）：旧问题不得因新问题到来而失效 ──────────

  test('M1 并存：挂 Q1 → 挂 Q2，Q1 的可答状态不被后到者改动', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((m) => window.__note(m), ask('asknb-m-q1'));
    await page.evaluate(() => window.__cardIn('asknb-m-q1'));
    await page.evaluate((m) => window.__note(m), ask('asknb-m-q2'));
    await settle(page);

    expect(await page.evaluate(() => window.__count())).toBe(2);
    expect(await page.evaluate(() => window.__placement('asknb-m-q1'))).toBe('inline');
    expect(await page.evaluate(() => window.__placement('asknb-m-q2'))).toBe('todo');
    expect(await page.evaluate(() => window.__cardState('asknb-m-q1'))).toBe('open');
    expect(await page.evaluate(() => window.__todoIds())).toEqual(['asknb-m-q2']);
    expect(await page.evaluate(() => window.__barRows())).toBe(1);
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: true, count: '1' });
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('M2 正序：回翻答 Q1（成功）⇒ Q2 不受影响；再从条目答 Q2 ⇒ 两侧同步收口', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((m) => window.__note(m), ask('asknb-m2-q1'));
    await page.evaluate(() => window.__cardIn('asknb-m2-q1'));
    await page.evaluate((m) => window.__note(m), ask('asknb-m2-q2'));
    await settle(page);

    const a1 = await page.evaluate(() => window.__answerViaCard('asknb-m2-q1', ['甲']));
    expect(a1).toEqual({ ok: true });
    await settle(page);
    expect(await page.evaluate(() => window.__cardState('asknb-m2-q1'))).toBe('locked');
    expect(await page.evaluate(() => window.__placement('asknb-m2-q1'))).toBe(null);
    expect(await page.evaluate(() => window.__count())).toBe(1);
    expect(await page.evaluate(() => window.__todoIds())).toEqual(['asknb-m2-q2']);
    expect(await page.evaluate(() => window.__placement('asknb-m2-q2'))).toBe('todo');

    expect(await page.evaluate(() => window.__todoPick('asknb-m2-q2', '乙'))).toBe(true);
    expect(await page.evaluate(() => window.__todoSubmit('asknb-m2-q2'))).toBe(true);
    await settle(page);
    expect(await page.evaluate(() => window.__count())).toBe(0);
    expect(await page.evaluate(() => window.__todoCards())).toBe(0);
    expect(await page.evaluate(() => window.__barRows())).toBe(0);
    expect(await page.evaluate(() => window.__badge())).toEqual({ visible: false, count: '0' });

    const frames = await page.evaluate(() => window.__captured.filter(f => f.type === 'askUserAnswer'));
    expect(frames).toHaveLength(2);
    expect(frames.map(f => [f.requestId, f.answers[0]])).toEqual([['asknb-m2-q1', '甲'], ['asknb-m2-q2', '乙']]);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('M3 反序：先答 Q2（条目侧）再回翻答 Q1 ⇒ 无顺序依赖，结果同 M2', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((m) => window.__note(m), ask('asknb-m3-q1'));
    await page.evaluate(() => window.__cardIn('asknb-m3-q1'));
    await page.evaluate((m) => window.__note(m), ask('asknb-m3-q2'));
    await settle(page);

    expect(await page.evaluate(() => window.__todoPick('asknb-m3-q2', '乙'))).toBe(true);
    expect(await page.evaluate(() => window.__todoSubmit('asknb-m3-q2'))).toBe(true);
    await settle(page);
    expect(await page.evaluate(() => window.__cardState('asknb-m3-q1'))).toBe('open');
    expect(await page.evaluate(() => window.__placement('asknb-m3-q1'))).toBe('inline');
    expect(await page.evaluate(() => window.__count())).toBe(1);
    expect(await page.evaluate(() => window.__answerViaCard('asknb-m3-q1', ['甲']))).toEqual({ ok: true });
    await settle(page);
    expect(await page.evaluate(() => window.__cardState('asknb-m3-q1'))).toBe('locked');
    expect(await page.evaluate(() => window.__count())).toBe(0);
    const frames = await page.evaluate(() => window.__captured.filter(f => f.type === 'askUserAnswer'));
    expect(frames.map(f => [f.requestId, f.answers[0]])).toEqual([['asknb-m3-q2', '乙'], ['asknb-m3-q1', '甲']]);
    expect(pageErrors).toEqual([]);
    await context.close();
  });

  test('M4 各自收口：先答 Q1 不得令 Q2 变已答（无跨 id 牵连）', async ({ browser }) => {
    const { context, page, pageErrors } = await newPage(browser);
    await page.evaluate((m) => window.__note(m), ask('asknb-m4-q1'));
    await page.evaluate((m) => window.__note(m), ask('asknb-m4-q2'));
    await settle(page);
    expect(await page.evaluate(() => window.__todoIds())).toEqual(['asknb-m4-q1', 'asknb-m4-q2']);
    expect(await page.evaluate(() => window.__submit({ requestId: 'asknb-m4-q1', answers: ['甲'] }))).toEqual({ ok: true });
    await settle(page);
    expect(await page.evaluate(() => window.__todoIds())).toEqual(['asknb-m4-q2']);
    expect(await page.evaluate(() => window.__placement('asknb-m4-q2'))).toBe('todo');
    expect(await page.evaluate(() => window.__todoPick('asknb-m4-q2', '乙'))).toBe(true);
    expect(await page.evaluate(() => window.__todoSubmit('asknb-m4-q2'))).toBe(true);
    await settle(page);
    expect(await page.evaluate(() => window.__count())).toBe(0);
    expect(pageErrors).toEqual([]);
    await context.close();
  });
});

test.describe('源码闸双向退出码（破坏 → 红 / 还原 → 绿）', () => {
  const GATE = 'scripts/check-ask-todo-lifecycle.mjs';
  const RELS = [
    'js/askPending.js',
    'js/askTodoQuestion.js',
    'js/chat.js',
    'js/todoStore.js',
    'js/mobile/todoAppleFace.js',
    'js/todoFace.js',
  ];

  function makeTree(mutate) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ask-todo-gate-'));
    for (const rel of RELS) {
      const dst = path.join(dir, rel);
      fs.mkdirSync(path.dirname(dst), { recursive: true });
      fs.copyFileSync(path.join(WEB, rel), dst);
    }
    if (mutate) mutate(dir);
    return dir;
  }
  const runGate = (root) => spawnSync(process.execPath, [GATE, `--web-root=${root}`], {
    cwd: REPO_ROOT, encoding: 'utf8',
  });
  /** 破坏 → 红（含该码）→ 还原 → 绿。 */
  function breakThenRestore(mutate, code) {
    const dir = makeTree(mutate);
    const broken = runGate(dir);
    expect(broken.status).toBe(1);
    expect(broken.stderr).toContain(code);
    fs.rmSync(dir, { recursive: true, force: true });
    const again = makeTree();
    expect(runGate(again).status).toBe(0);
    fs.rmSync(again, { recursive: true, force: true });
  }

  test('G0 未改源码 ⇒ 绿（exit 0）', () => {
    const dir = makeTree();
    expect(runGate(dir).status).toBe(0);
    fs.rmSync(dir, { recursive: true, force: true });
  });

  test('G1 破坏「对话面只画 inline」 ⇒ 红（A2）；还原 ⇒ 绿', () => {
    breakThenRestore((d) => {
      const f = path.join(d, 'js/askPending.js');
      fs.writeFileSync(f, fs.readFileSync(f, 'utf8').replace('const entries = inlineAsks();', 'const entries = orderedEntries();'));
    }, 'A2');
  });

  test('G2 破坏「条目内问题卡复用了对话内活卡定位面」 ⇒ 红（A5）；还原 ⇒ 绿', () => {
    breakThenRestore((d) => {
      const f = path.join(d, 'js/askTodoQuestion.js');
      fs.writeFileSync(f, fs.readFileSync(f, 'utf8').replace('card.dataset.askTodoId = entry.requestId;', 'card.dataset.requestId = entry.requestId;'));
    }, 'A5');
  });

  test('G3 破坏「答复帧单点」 ⇒ 红（A4）；还原 ⇒ 绿', () => {
    breakThenRestore((d) => {
      const f = path.join(d, 'js/chat.js');
      fs.writeFileSync(f, fs.readFileSync(f, 'utf8').replace(
        'askAnswerFrame({\n          sessionId: targetSid,',
        "{ type: 'askUserAnswer',\n          sessionId: targetSid,",
      ));
    }, 'A4');
  });

  test('G4 注入「后到问题使前一个失效」（新帧整表清空）⇒ 红（A7）；还原 ⇒ 绿', () => {
    breakThenRestore((d) => {
      const f = path.join(d, 'js/askPending.js');
      fs.writeFileSync(f, fs.readFileSync(f, 'utf8').replace(
        'pending.set(msg.requestId, toEntry(msg));',
        'pending.clear();\n  pending.set(msg.requestId, toEntry(msg));',
      ));
    }, 'A7');
  });

  test('G5 未修源码（本批之前的 web 树）直跑 ⇒ 红（exit 1）', () => {
    let mainRoot = null;
    try {
      const r = spawnSync('git', ['rev-parse', '--path-format=absolute', '--git-common-dir'],
        { cwd: REPO_ROOT, encoding: 'utf8' });
      if (r.status === 0) mainRoot = path.dirname(r.stdout.trim());
    } catch { /* fall through to skip */ }
    const baselineWeb = mainRoot ? path.join(mainRoot, 'src', 'main', 'resources', 'web') : null;
    const usable = !!baselineWeb && fs.existsSync(path.join(baselineWeb, 'js', 'askPending.js'))
      && !fs.readFileSync(path.join(baselineWeb, 'js', 'askPending.js'), 'utf8').includes('askPlacement');
    test.skip(!usable, 'unmodified main-checkout web tree unavailable (already carries this batch)');
    const r = runGate(baselineWeb);
    expect(r.status).toBe(1);
    expect(r.stderr).toContain('A1');
  });

  // ── 作者新令点名三条（(a) 同源条目 · (b) 无上方区块 · (c) 两侧同步收口）──────
  test('G6 (a) 破坏「条目身份映射（来源标记 / requestId）」 ⇒ 红（A8）；还原 ⇒ 绿', () => {
    breakThenRestore((d) => {
      const f = path.join(d, 'js/todoStore.js');
      fs.writeFileSync(f, fs.readFileSync(f, 'utf8').replace("source: askRid ? 'ask' : null,", 'source: null,'));
    }, 'A8');
  });

  test('G7 (b) 把「待办上方 ask 专用区块」加回去 ⇒ 红（A9）；还原 ⇒ 绿', () => {
    breakThenRestore((d) => {
      const f = path.join(d, 'js/todoFace.js');
      fs.writeFileSync(f, fs.readFileSync(f, 'utf8') + '\nmountAskTodoSection(this.listInner);\n');
    }, 'A9');
  });

  test('G8 (c) 破坏「两侧同步收口」（条目问题卡不再走单点作答） ⇒ 红（A10）；还原 ⇒ 绿', () => {
    breakThenRestore((d) => {
      const f = path.join(d, 'js/askTodoQuestion.js');
      fs.writeFileSync(f, fs.readFileSync(f, 'utf8').replace('const res = submitAskAnswer({', 'const res = answerDirect({'));
    }, 'A10');
  });
});
