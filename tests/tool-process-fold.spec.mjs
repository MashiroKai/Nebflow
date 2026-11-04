// tool-process-fold.spec.mjs — 「工具/思考过程泄漏」修复批（作者令 2026-11-04 14:14）
//
// 作者原话（摘要）：
//   ① 「现在主会话运行时是不显示工具和思考过程的……只有在完成之后，点击完成的
//      文字展开，才能看到具体的思考和工具调用过程。现在的问题是，在某些情况下
//      （比如断网或者刷新页面），页面就不会走那行流式输出来掩盖执行过程的样式，
//      而是直接把所有的思考和工具过程全部呈现出来，这就是我说的泄露。」
//   ② 「不知道哪一次前端优化把工具过程展示得特别长、内容特别多。之前都只是一行
//      简单的摘要，点击后才会呈现结果；现在还没点击，内容和结果就已经很长了。」
//
// 判据面 = 默认态（未点击）的 **渲染后 DOM 指纹**：
//   · 折叠态 class（`.nf-tucked` / `.turn-header[data-turn-state]` / aria-expanded）
//   · 明细可见性（`.body` / `.ds-tool-card__preview` / `.thinking-content` 高度）
//   · 可见文本长度（逐行只算几何高度 > 0.5px 的直系文本）
//   · 卡片高度（工具卡 / 思考行 / 整容器）
// 同一段会话（thinking + 2 次工具调用 + 24 行长输出 + terminal 行）分别取
// 「流式刚完成」与「刷新回放」两个时刻 —— 两者必须逐项相等。
//
// 跑法（绿腿）：
//   node node_modules/@playwright/test/cli.js test tests/tool-process-fold.spec.mjs
// 红腿（对修复前的树；never `git checkout main`，工作树不被触碰）：
//   mkdir -p /tmp/nb-fold-base
//   git -C <repo> archive <base-sha> src/main/resources/web | tar -x -C /tmp/nb-fold-base
//   NB_UX_ROOT=/tmp/nb-fold-base PIN_EXPECT=red \
//     node node_modules/@playwright/test/cli.js test tests/tool-process-fold.spec.mjs
//
// PIN_EXPECT=red 时**新行为钉**方向反转（要求缺陷在场）；守卫钉（摘要行入口、
// 展开可用、零页面错误）两向都要求成立。

import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createReplayServer } from './helpers/replay-ws-server.mjs';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/tool-process-fold/harness.html';
const HARNESS_FILE = path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, ''));
const FIXTURE_DIR = path.join(REPO_ROOT, 'tests/fixtures/tool-process-fold');
const FIXTURE = JSON.parse(fs.readFileSync(path.join(FIXTURE_DIR, 'fixture.json'), 'utf8'));
const MSGS = FIXTURE.messages;
// 在飞尾段（断网/刷新时该轮还没收尾：末段无 terminal 戳、无 done 徽章）
const BUSY = JSON.parse(fs.readFileSync(path.join(FIXTURE_DIR, 'fixture-busy.json'), 'utf8')).messages;

// 实测默认态单行摘要高度闸（桌面 1440 档）：工具卡一行 = icon + 单行 label +
// 设备角标。改后实测值见 README 读数表；容差 12px 覆盖字体度量抖动。
const CARD_ONE_LINE_PX = 35;
const CARD_TOLERANCE_PX = 12;

const SERVE_ROOT = process.env.NB_UX_ROOT || REPO_ROOT;
const RED = process.env.PIN_EXPECT === 'red';
const SHOT_DIR = process.env.NB_UX_SHOT_DIR ||
  '/Users/kaiyu/.stand/evidence/ui-unification-20261103/process-leak';

/** 新行为钉：绿腿要求新行为在场；红腿（对修复前树）要求缺陷在场。 */
function pinNewFace(holds, desc) {
  if (RED) expect(holds, `红腿要求缺陷在场：${desc}`).toBe(false);
  else expect(holds, `绿腿要求新行为在场：${desc}`).toBe(true);
}
/** 守卫钉：两棵树都必须成立。 */
function pinGuard(holds, desc) {
  expect(holds, `守卫钉（${RED ? '红腿' : '绿腿'}）：${desc}`).toBe(true);
}

let srv;
/** getHistory 载荷开关（socket 腿按场景切换：已完成轮 / 在飞轮）。 */
let servedMessages = MSGS;
test.beforeAll(async () => {
  // 红腿自举：基线树只导出 src/main/resources/web ⇒ 把 harness + fixture 覆盖
  // 复制过去（覆盖 = /tmp 陈年副本不会静默失效）。
  const serveHarness = path.join(SERVE_ROOT, HARNESS_PATH.replace(/^\//, ''));
  if (path.resolve(serveHarness) !== path.resolve(HARNESS_FILE)) {
    fs.mkdirSync(path.dirname(serveHarness), { recursive: true });
    fs.copyFileSync(HARNESS_FILE, serveHarness);
    const fx = path.join(SERVE_ROOT, 'tests/fixtures/tool-process-fold');
    fs.mkdirSync(fx, { recursive: true });
    for (const f of ['fixture.json', 'fixture-busy.json']) {
      fs.copyFileSync(path.join(FIXTURE_DIR, f), path.join(fx, f));
    }
  }
  expect(fs.existsSync(serveHarness)).toBe(true);
  srv = await createReplayServer({ root: path.resolve(SERVE_ROOT), messages: () => servedMessages });
  expect(srv.port).not.toBe(8080); // host discipline: never touch the host port
});

test.afterAll(async () => {
  if (srv) await srv.close();
});

async function newPage(browser, viewport = { width: 1440, height: 900 }) {
  const context = await browser.newContext({ locale: 'zh-CN', viewport });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${srv.port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

/** 默认态指纹投影：只取「过程面」——摘要行、过程行的折叠/可见性/可见文本、
 *  工具卡与思考块的可见高度。footer 徽章等消息面元数据不在本判据内。 */
function project(fp) {
  return {
    header: {
      present: fp.header.present,
      turnState: fp.header.turnState || '',
      ariaExpanded: fp.header.ariaExpanded || null,
    },
    counts: {
      headers: fp.headers,
      toolRows: fp.toolRows,
      toolRowsTucked: fp.toolRowsTucked,
      thinkRows: fp.thinkRows,
      thinkRowsTucked: fp.thinkRowsTucked,
    },
    cards: fp.cardFaces.map((c) => ({
      rowTucked: c.rowTucked,
      rowHeight: Math.round(c.rowHeight),
      cardHeight: Math.round(c.cardHeight),
      previewHeight: Math.round(c.previewHeight),
      bodyHeight: Math.round(c.bodyHeight),
      preLines: c.preLines,
      imgRefs: c.imgRefs,
    })),
    thinks: fp.thinkFaces.map((t) => ({
      rowTucked: t.rowTucked,
      rowHeight: Math.round(t.rowHeight),
      contentDisplay: t.contentDisplay,
      contentHeight: Math.round(t.contentHeight),
    })),
    processVisibleText: fp.rowHeights
      .filter((r) => /(^|\s)(tool|thinking-row)(\s|$)/.test(r.cls))
      .reduce((a, r) => a + r.visibleText, 0),
  };
}

/** 服务端硬断开关挂载（真实 socket 层断开 = 断网；客户端看到 onclose → 退避重连）。 */
async function armDrop(page) {
  await page.exposeBinding('__peerDropBind', () => { srv.dropAll(); });
  await page.evaluate(() => { window.__peerDropAll = window.__peerDropBind; });
}

// ── A. 回放一致性（P1 主判据）────────────────────────────────────────────
test('A 流式完成态 vs 刷新回放态：默认态 DOM 指纹逐项相等', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser);
  try {
    const live = await page.evaluate(() => window.__liveTurn({}));
    const replay = await page.evaluate((msgs) => window.__replayTurn(msgs, {}), MSGS);

    // 读数原文（失败时一眼可读；同时是 README 的改前/改后表）
    console.log('[A] live.project   =', JSON.stringify(project(live)));
    console.log('[A] replay.project =', JSON.stringify(project(replay)));

    // 守卫：两个时刻都必须有「一行摘要」入口（可点 header）
    pinGuard(live.header.present, '流式完成态存在 .turn-header 摘要行');
    pinGuard(live.toolRows === 2, `流式完成态 2 张工具卡（got ${live.toolRows}）`);
    pinGuard(replay.toolRows === 2, `回放态 2 张工具卡（got ${replay.toolRows}）`);

    // 主判据：逐项相等（红腿基线：回放态 thinking 正文与工具卡明细全可见 ⇒ 不等）
    expect(project(replay), '回放默认态指纹必须与流式完成态逐项相等')
      .toEqual(project(live));
    pinGuard(pageErrors.length === 0, `零页面错误（${pageErrors.join(' | ')}）`);
  } finally { await context.close(); }
});

// ── A2. 冷刷新时该轮仍在飞（P1 主案发现场）─────────────────────────────
test('A2 冷刷新（该轮未收尾，末段无终态判据）：过程行按 live 出生态折叠', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser);
  try {
    // 作者原话场景：「我在回复中，然后我网络中断或者刷新了页面，再次进来的时候
    // 就会看到你的完整的工具执行过程」。F5 后 busySessionIds 是空的（内存态）
    // ⇒ 末段既非 confirmed、也非 busyTail，改前的 summarizeSegment 直接平铺。
    const fp = await page.evaluate((msgs) => window.__replayTurn(msgs, { busy: false }), BUSY);
    console.log('[A2] replay.project =', JSON.stringify(project(fp)));
    pinNewFace(fp.toolRowsTucked === fp.toolRows,
      `未收尾轮的工具卡折叠（got ${fp.toolRowsTucked}/${fp.toolRows}）`);
    pinNewFace(fp.thinkRowsTucked === fp.thinkRows,
      `未收尾轮的思考行折叠（got ${fp.thinkRowsTucked}/${fp.thinkRows}）`);
    // P2 面：折叠态默认面不得渲染正文行（改前 = 8 行 preview 常驻 + 卡高 297px）
    pinNewFace(fp.cardFaces.every((c) => c.preLines === 0),
      `未收尾轮工具卡默认面不渲染正文（got ${JSON.stringify(fp.cardFaces.map((c) => c.preLines))} 行）`);
    pinNewFace(project(fp).processVisibleText <= 1,
      `未收尾轮过程面可见文本 ≈ 0（got ${project(fp).processVisibleText} 字）`);
    pinNewFace(fp.header.present, '未收尾轮有可点摘要行（明细不丢：点开即见）');
    // 展开后明细完整回归（内容不丢的机械证明）
    await page.evaluate(() => window.__clickHeader());
    await page.waitForTimeout(80);
    const expanded = await page.evaluate(() => window.__fingerprint());
    pinGuard(expanded.toolRowsTucked === 0, `点开摘要行 ⇒ 过程行全部可见（got tucked ${expanded.toolRowsTucked}）`);
    pinGuard(expanded.cardFaces[0].cardHeight > 20, '点开后工具卡回到可见面');
    pinGuard(pageErrors.length === 0, `零页面错误（${pageErrors.join(' | ')}）`);
  } finally { await context.close(); }
});

// ── A3. 中性摘要行 + live 收尾 = 采用同一枚（绝不并列两枚）─────────────────
test('A3 回放建的中性摘要行被 live 终态采用：全场仍恰一枚摘要行', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser);
  try {
    const before = await page.evaluate((msgs) => window.__replayTurn(msgs, { busy: true }), BUSY);
    // 改前（红腿）：在飞回放零摘要行（整段平铺）⇒ 新面钉方向反转成立。
    pinNewFace(before.headers === 1, `在飞回放恰一枚摘要行（got ${before.headers}）`);
    const neutral = await page.evaluate(() => {
      const h = document.querySelector('#chat > .turn-header');
      return {
        unconfirmed: h ? h.dataset.nfTurnUnconfirmed === '1' : null,
        text: h ? (h.querySelector('.turn-header-text') || {}).textContent || '' : '',
      };
    });
    pinNewFace(neutral.unconfirmed === true && !/到站/.test(neutral.text),
      `中性摘要行带 data-nf-turn-unconfirmed 且不含成功文案（got ${JSON.stringify(neutral)}）`);

    const after = await page.evaluate(() => window.__finishLiveTurn({}));
    console.log('[A3] after.project =', JSON.stringify(project(after)));
    // 收尾后的结构契约（绿腿；红腿在飞面本就无摘要行，此处不适用）
    if (!RED) {
      pinGuard(after.headers === 1, `live 收尾后仍恰一枚摘要行（got ${after.headers}）`);
      const done = await page.evaluate(() => {
        const h = document.querySelector('#chat > .turn-header');
        return { unconfirmed: h ? h.dataset.nfTurnUnconfirmed === '1' : null,
          text: h ? (h.querySelector('.turn-header-text') || {}).textContent || '' : '' };
      });
      pinGuard(done.unconfirmed === false, '收尾后中性标记卸下（同一枚节点被采用改写）');
      pinGuard(/harness-model/.test(done.text), `收尾后摘要行带模型段（got ${JSON.stringify(done.text)}）`);
      pinGuard(after.toolRowsTucked === after.toolRows, '收尾后过程行仍折叠');
    }
    pinGuard(pageErrors.length === 0, `零页面错误（${pageErrors.join(' | ')}）`);
  } finally { await context.close(); }
});

// ── B. 断网重连（socket 硬断 → 重连 → 重放）──────────────────────────────
test('B 断网重连（该轮已完成）：重放默认态不得出现明细正文', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser);
  servedMessages = MSGS;
  try {
    await armDrop(page);
    const cycle = await page.evaluate(([msgs]) => window.__socketCycle(msgs, { busy: false, drops: 1 }), [MSGS]);
    console.log('[B] socket cycle =', JSON.stringify(cycle));
    pinGuard(cycle.replays >= 2, `断网重连至少两帧重放（首帧 + 重连帧；got ${cycle.replays}）`);

    const after = await page.evaluate(() => window.__fingerprint());
    console.log('[B] after.project  =', JSON.stringify(project(after)));
    // 守卫：该轮已完成（段内有 writer terminal 戳）⇒ 两棵树都该折叠（改前已由
    // replay-terminal 批覆盖）。本批的差异面在「无终态判据」的段（A2/B2/E）。
    pinGuard(after.toolRowsTucked === after.toolRows,
      `重连后工具卡全部折叠（got ${after.toolRowsTucked}/${after.toolRows}）`);
    pinGuard(after.cardFaces.every((c) => c.bodyHeight === 0),
      `重连后工具卡明细体高度为 0（got ${JSON.stringify(after.cardFaces.map((c) => c.bodyHeight))}）`);
    pinGuard(after.thinkFaces.every((t) => t.contentHeight === 0),
      `重连后思考正文高度为 0（got ${JSON.stringify(after.thinkFaces.map((t) => t.contentHeight))}）`);
    pinGuard(after.header.present, '重连后摘要行在场（可点展开）');
    pinGuard(pageErrors.length === 0, `零页面错误（${pageErrors.join(' | ')}）`);
  } finally { await context.close(); }
});

// ── B2. 断网时该轮仍在飞（busyTail 腿）──────────────────────────────────
test('B2 断网重连（断线时该轮仍在飞）：默认态同样不得摊开过程行', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser);
  servedMessages = BUSY;
  try {
    await armDrop(page);
    const cycle = await page.evaluate(([msgs]) => window.__socketCycle(msgs, { busy: true, drops: 1 }), [BUSY]);
    console.log('[B2] socket cycle =', JSON.stringify(cycle));
    const after = await page.evaluate(() => window.__fingerprint());
    console.log('[B2] after.project =', JSON.stringify(project(after)));
    // 在飞轮的 live 默认态 = 过程行出生即折叠（chat.js 单行揭示剧场）⇒ 回放同形
    pinNewFace(after.toolRowsTucked === after.toolRows,
      `在飞轮重放后工具卡全部折叠（got ${after.toolRowsTucked}/${after.toolRows}）`);
    pinNewFace(after.thinkRowsTucked === after.thinkRows,
      `在飞轮重放后思考行全部折叠（got ${after.thinkRowsTucked}/${after.thinkRows}）`);
    pinNewFace(after.cardFaces.every((c) => c.preLines === 0),
      `在飞轮重放后工具卡默认面不渲染正文（got ${JSON.stringify(after.cardFaces.map((c) => c.preLines))} 行）`);
    pinGuard(pageErrors.length === 0, `零页面错误（${pageErrors.join(' | ')}）`);
  } finally { await context.close(); }
});

// ── C. 默认态高度闸（P2）────────────────────────────────────────────────
test('C 折叠态高度闸：工具卡 ≤ 单行摘要 + 容差；思考块默认只一行', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  try {
    const live = await page.evaluate(() => window.__liveTurn({}));
    // 展开整轮过程后，逐卡默认面（未点卡）= 一行摘要
    await page.evaluate(() => window.__clickHeader());
    await page.waitForTimeout(80);
    const expanded = await page.evaluate(() => window.__fingerprint());
    console.log('[C] live.cards      =', JSON.stringify(live.cardFaces.map((c) => ({ h: Math.round(c.cardHeight), preview: Math.round(c.previewHeight), pre: c.preLines, body: Math.round(c.bodyHeight) }))));
    console.log('[C] expanded.cards  =', JSON.stringify(expanded.cardFaces.map((c) => ({ h: Math.round(c.cardHeight), preview: Math.round(c.previewHeight), pre: c.preLines, body: Math.round(c.bodyHeight) }))));
    console.log('[C] expanded.thinks =', JSON.stringify(expanded.thinkFaces));

    const limit = CARD_ONE_LINE_PX + CARD_TOLERANCE_PX;
    // 新面钉打在**长输出卡**（fixture: 24 行输出的 Bash 卡 = 作者报障的撑长卡）：
    // 改前 297px / 8 行 preview，改后 12px / 0 行。
    const longCard = expanded.cardFaces[0];
    pinNewFace(longCard.cardHeight <= limit,
      `长输出卡默认高度 ${Math.round(longCard.cardHeight)}px ≤ ${limit}px（单行 ${CARD_ONE_LINE_PX}px + 容差 ${CARD_TOLERANCE_PX}px）`);
    pinNewFace(longCard.preLines === 0, `长输出卡默认面不渲染正文行（got ${longCard.preLines} 行）`);
    // 短内容卡（第二张）：改前本来就在闸内（不撑长）⇒ 只作绿腿的不变量。
    if (!RED) {
      for (const c of expanded.cardFaces.slice(1)) {
        pinGuard(c.cardHeight <= limit, `工具卡默认高度 ${Math.round(c.cardHeight)}px ≤ ${limit}px`);
      }
    }
    for (const t of expanded.thinkFaces) {
      // 思考正文默认 0 高 + 摘要单行：两棵树都成立（改前思考行本就是一行标签）。
      pinGuard(t.contentHeight === 0, `思考正文默认高度 0（got ${Math.round(t.contentHeight)}px）`);
      pinGuard(t.labelText.length > 0 && t.labelText.split('\n').length === 1,
        `思考块默认只显示一行摘要（got ${JSON.stringify(t.labelText)}）`);
    }
  } finally { await context.close(); }
});

// ── D. 展开仍可用（内容不丢）────────────────────────────────────────────
test('D 展开仍可用：点摘要 ⇒ 明细完整呈现；再点收回', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  try {
    await page.evaluate((msgs) => window.__replayTurn(msgs, {}), MSGS);
    // ① 摘要行点开 = 过程行回到原位可见（内容一个字都不丢）
    await page.evaluate(() => window.__clickHeader());
    await page.waitForTimeout(80);
    let fp = await page.evaluate(() => window.__fingerprint());
    pinGuard(fp.toolRowsTucked === 0, `展开后工具卡全部可见（got tucked ${fp.toolRowsTucked}）`);
    pinGuard(fp.thinkRowsTucked === 0, `展开后思考行全部可见（got tucked ${fp.thinkRowsTucked}）`);
    pinGuard(fp.header.ariaExpanded === 'true', 'aria-expanded=true');
    // ② 卡内点击 = 长输出全文进 DOM（一次装配，收起即卸）
    await page.evaluate(() => window.__clickCard(0));
    await page.waitForTimeout(80);
    fp = await page.evaluate(() => window.__fingerprint());
    const big = fp.cardFaces[0];
    pinGuard(big.bodyOpen === true, '卡内点击后明细体打开');
    pinGuard(big.preLines >= 24, `长输出全文 ${big.preLines} 行 ≥ 24 行（内容不丢）`);
    pinGuard(big.bodyHeight > 100, `明细体高度 ${Math.round(big.bodyHeight)}px > 100`);
    // ③ 再点收回 = 回到一行摘要
    await page.evaluate(() => window.__clickCard(0));
    await page.waitForTimeout(80);
    fp = await page.evaluate(() => window.__fingerprint());
    pinGuard(fp.cardFaces[0].bodyOpen === false, '再点收回：明细体关闭');
    pinGuard(fp.cardFaces[0].bodyHeight === 0, `收回后明细体高度 0（got ${fp.cardFaces[0].bodyHeight}）`);
    // ④ 摘要行再点 = 过程行重新折叠
    await page.evaluate(() => window.__clickHeader());
    await page.waitForTimeout(80);
    fp = await page.evaluate(() => window.__fingerprint());
    pinGuard(fp.toolRowsTucked === fp.toolRows, '再点摘要：过程行重新折叠');
  } finally { await context.close(); }
});

// ── E. 失败轮：默认折叠但仍看得出成败（边界）──────────────────────────
test('E 失败轮：默认态同样折叠，但摘要行看得出是失败', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  try {
    // 失败轮的现实形态：writer 只在 done/agentDone 盖 terminal 戳 ⇒ 失败轮**无**
    // 终态戳（改前据此整段平铺 = 失败过程全裸奔）。本批 = 中性摘要行 + 折叠。
    const failing = [
      { type: 'user', text: '跑个会失败的', timestamp: 1750000100000 },
      { type: 'tool', label: 'Bash', summary: 'boom --run',
        input: { command: 'boom --run' },
        content: Array.from({ length: 12 }, (_, i) => `stderr line ${i}`).join('\n'),
        isError: true },
      { type: 'ai', text: '', durationMs: 4000, model: 'harness-model', timestamp: 1750000104000 },
    ];
    const fp = await page.evaluate((msgs) => window.__replayTurn(msgs, {}), failing);
    console.log('[E] fail.project =', JSON.stringify(project(fp)));
    pinNewFace(fp.cardFaces.every((c) => c.preLines === 0),
      `失败轮工具卡默认面不渲染正文（got ${JSON.stringify(fp.cardFaces.map((c) => c.preLines))} 行）`);
    // 折叠面本身：改前失败轮已有 done 徽章（durationMs）⇒ 两棵树都折（守卫）。
    pinGuard(fp.toolRowsTucked === fp.toolRows, '失败轮工具卡折叠（默认态不漏过程）');
    pinGuard(fp.header.present, '失败轮仍有摘要行（展开入口）');
    const face = await page.evaluate(() => {
      const card = document.querySelector('#chat > .row.tool .tool-card');
      return {
        errInk: !!(card && card.querySelector('.icon.err')),
        labelText: card ? (card.querySelector('.label') || {}).textContent || '' : '',
      };
    });
    console.log('[E] summary face =', JSON.stringify(face));
    pinGuard(face.errInk, '失败着色（.icon.err）在摘要面可见 —— 成败不被藏起来');
    pinGuard(/boom/.test(face.labelText), `摘要行带命令关键参数（got ${JSON.stringify(face.labelText)}）`);
  } finally { await context.close(); }
});

// ── F. 双端截图（改前/改后各一轮；tag 由 NB_UX_ROOT 派生）───────────────
for (const vp of [{ name: 'desktop', width: 1440, height: 900 }, { name: 'mobile', width: 375, height: 812 }]) {
  test(`F 截图 ${vp.name}：刷新回放默认态 + 展开态`, async ({ browser }) => {
    const { context, page } = await newPage(browser, { width: vp.width, height: vp.height });
    try {
      const tag = process.env.NB_UX_ROOT && path.resolve(process.env.NB_UX_ROOT) !== path.resolve(REPO_ROOT)
        ? 'before' : 'after';
      const dir = path.join(SHOT_DIR, tag);
      fs.mkdirSync(dir, { recursive: true });
      // ① 作者报障现场：刷新时该轮仍在飞（末段无终态判据）——改前 = 完整过程摊开
      await page.evaluate((msgs) => window.__replayTurn(msgs, { busy: false }), BUSY);
      await page.waitForTimeout(150);
      await page.screenshot({ path: path.join(dir, `process-leak-${vp.name}-1-replay-inflight.png`) });
      // ② 该轮已完成：折叠态（一行摘要）
      await page.evaluate((msgs) => window.__replayTurn(msgs, {}), MSGS);
      await page.waitForTimeout(150);
      await page.screenshot({ path: path.join(dir, `process-leak-${vp.name}-2-replay-completed.png`) });
      // ③ 点开摘要行 = 过程行原位回归
      await page.evaluate(() => window.__clickHeader());
      await page.waitForTimeout(100);
      await page.screenshot({ path: path.join(dir, `process-leak-${vp.name}-3-expanded-turn.png`) });
      // ④ 再点开卡 = 全文（内容不丢）
      await page.evaluate(() => window.__clickCard(0));
      await page.waitForTimeout(100);
      await page.screenshot({ path: path.join(dir, `process-leak-${vp.name}-4-expanded-card.png`) });
    } finally { await context.close(); }
  });
}
