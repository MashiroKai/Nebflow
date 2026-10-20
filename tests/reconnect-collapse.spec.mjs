// reconnect-collapse.spec.mjs — 「重连后 thinking/工具结果闪现 + 窗口抽动 +
// footer 胶囊跳动」家族回归（修复批 v2 · 重连家族 · chain bf0a8269，续作
// 69e5e2e1）。
//
// 判据面 = the RENDERED DOM / Layout Instability API / 节点身份引用读数，
// never the source wording——每一条探针都形态无关（不写被测代码新引入的
// 类名），因此同一份 spec 在基线树（main 导出）上 RED、在本树 GREEN：
//
//   node /path/to/main-repo/node_modules/@playwright/test/cli.js \
//     test tests/reconnect-collapse.spec.mjs
//   # 红腿（never `git checkout main`; the working tree is untouched）：
//   mkdir -p /tmp/nb-reconnect-base
//   git -C <repo> archive main src/main/resources/web | tar -x -C /tmp/nb-reconnect-base
//   NB_UX_ROOT=/tmp/nb-reconnect-base PIN_EXPECT=red \
//     node .../cli.js test tests/reconnect-collapse.spec.mjs
//   # （PIN_EXPECT=red 时断言方向反转：要求缺陷在场，双向全绿 —— 红腿证据跑法）
//
// 七组断言：
//   A live 控制组          — live 流可正常构建（证明钉不是恒红）
//   B 重连中思考重建面      — busy + 无 text 的重连重建面：thinking 内容
//                            display:none 恒成立（症状① mid-turn 腿）
//   C 纯思考历史行          — 历史里 thinking-only 的 ai 行重放后恒折叠
//                            （症状①历史腿；旧「无 text 保持展开」例外已废）
//   D 工具结果体            — 重放/重建的工具卡 body 不自动 .open（症状①工具腿）
//   E 抽动阈值              — Layout Instability API：重连重放全程 CLS 低于
//                            阈值；用户停在中段时 scrollTop 不跳（重连不劫持视口）
//   F 幂等重放 + footer 零位移 — 同帧重复重放：DOM 节点身份不变（零重挂载）、
//                            footer 胶囊矩形逐像素相等（零位移）、子元素计数
//                            不变（#570 重复气泡家族封口）、用户展开过的
//                            thinking 行跨重放保持展开（折叠态跨重放持久）
//   G CDP 节流 + WS 强制重连循环 — 真实 ws.js 机关（connect/onReconnect/
//                            forceReconnect/心跳）对迷你 RFC6455 peer，CDP
//                            emulateNetworkConditions 节流下循环强制重连 ×3：
//                            三症状（闪现/抽动/跳动）在循环全程不出现
//
// 静态+WS 服务器由本 spec 自起自灭（tests/helpers/replay-ws-server.mjs，
// 随机高位端口，host :8080 永不触碰）。

import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createReplayServer } from './helpers/replay-ws-server.mjs';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/reconnect-collapse/harness.html';
const HARNESS_FILE = path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, ''));
const SHOT_DIR = process.env.NB_UX_SHOT_DIR ||
  path.join(os.homedir(), '.nebflow', 'docs', 'Nebflow');

// 被测树 = NB_UX_ROOT（红腿：main 导出的基线树）或本 worktree（绿腿）。
const SERVE_ROOT = process.env.NB_UX_ROOT || REPO_ROOT;
const RED = process.env.PIN_EXPECT === 'red';

/** 症状钉：绿腿要求缺陷缺席；红腿（PIN_EXPECT=red，对修复前树）要求缺陷
 *  在场——同一命令双向全绿，红腿输出即复现证据。 */
function pinDefectAbsent(defectPresent, desc) {
  if (RED) expect(defectPresent, `红腿要求缺陷在场：${desc}`).toBe(true);
  else expect(defectPresent, `绿腿要求缺陷缺席：${desc}`).toBe(false);
}

/** 守卫钉：行为在基线树与本树都该成立（如工具卡恒折叠/CLS 阈值）——
 *  两向都要求缺陷缺席，不随 PIN_EXPECT 反转。 */
function pinGuard(defectPresent, desc) {
  expect(defectPresent, `守卫钉：缺陷必须缺席（${RED ? '红腿' : '绿腿'}）：${desc}`).toBe(false);
}

// 真 history 形状（SessionRecorder.flushTextWithMeta 落盘口径）：
// 有 text 的 ai 行（thinking 随行）+ thinking-only 行 + tool 行。
const HISTORY_BUSY = [
  { type: 'user', text: '帮我看一下文件', timestamp: 1750000000000 },
  {
    type: 'ai',
    text: '先读一下入口文件。',
    thinking: '我先梳理一下当前文件的结构：\n\n- 入口在 main.scala\n- 核心逻辑分三层',
    timestamp: 1750000001000,
  },
  {
    type: 'tool',
    label: 'Read',
    summary: 'src/main/scala/Main.scala',
    content: 'package example\n\nobject Main extends App {\n  println("hello")\n}\n',
    isError: false,
    input: {},
  },
  { type: 'user', text: '再想一下边界条件', timestamp: 1750000002000 },
  {
    type: 'ai',
    text: '',
    thinking: '完整思考过程：第一步检查空输入，第二步检查越界，第三步结论成立。',
    timestamp: 1750000003000,
  },
];

const RECONNECT_THINKING = '继续思考：第二点需要再核对一遍当前实现细节。';

let srv;
test.beforeAll(async () => {
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  // 红腿引导：基线树只导出了 src/main/resources/web，harness 不在其内 ⇒
  // 把本树的 harness **覆盖**复制过去（history-replay-cards 同款自举；
  // 覆盖 = /tmp 里的陈年副本不会让新探针静默失效）。
  const serveHarness = path.join(SERVE_ROOT, HARNESS_PATH.replace(/^\//, ''));
  if (path.resolve(serveHarness) !== path.resolve(HARNESS_FILE)) {
    fs.mkdirSync(path.dirname(serveHarness), { recursive: true });
    fs.copyFileSync(HARNESS_FILE, serveHarness);
  }
  expect(fs.existsSync(serveHarness)).toBe(true);
  srv = await createReplayServer({ root: path.resolve(SERVE_ROOT) });
  expect(srv.port).not.toBe(8080); // host discipline: never touch the host port
});

test.afterAll(async () => {
  if (srv) await srv.close();
});

async function newPage(browser) {
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 900, height: 1000 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err.message));
  await page.goto(`http://127.0.0.1:${srv.port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 15000 });
  return { context, page, pageErrors };
}

/** 注入 Layout Instability 观察器；返回读数函数（CLS 累计 + 单条位移峰值）。 */
async function armShiftObserver(page) {
  await page.evaluate(() => {
    window.__cls = { value: 0, peak: 0, entries: [] };
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) {
        if (e.hadRecentInput) continue;
        window.__cls.value += e.value;
        window.__cls.entries.push({ value: e.value });
      }
      window.__cls.peak = Math.max(window.__cls.peak,
        ...window.__cls.entries.map((x) => x.value), 0);
    }).observe({ type: 'layout-shift', buffered: false });
  });
}
async function readShift(page) {
  return page.evaluate(() => ({ ...window.__cls }));
}

// ── A. live 控制组 ─────────────────────────────────────────────────────
test('A live 控制组：live 流可构建、无页面错误（证明钉非恒红）', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser);
  try {
    const snap = await page.evaluate(() => window.__live({ midThinking: true }));
    expect(snap.thinkingRows.length).toBe(1);
    expect(pageErrors).toEqual([]);
  } finally { await context.close(); }
});

// ── B. 重连中思考重建面（症状① mid-turn 腿） ──────────────────────────
test('B 重连重建面：busy+无text 时 thinking 内容恒折叠（症状①复刻）', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  try {
    const result = await page.evaluate(async (thinkBuf) => {
      window.__live({ midThinking: true });
      return await window.__reconnect(
        [
          { type: 'user', text: '帮我看一下文件', timestamp: 1750000000000 },
          {
            type: 'ai',
            text: '先读一下入口文件。',
            thinking: '我先梳理一下当前文件的结构：\n\n- 入口在 main.scala\n- 核心逻辑分三层',
            timestamp: 1750000001000,
          },
        ],
        { thinkBuf, txtBuf: '', stillBusy: true },
      );
    }, RECONNECT_THINKING);
    const { after } = result;
    // 症状①读数形态 = 「THINKING done 文案 + 内容可见」。恒折叠断言：
    // 重连后不得出现 content 可见（display !== 'none'）的 thinking 行。
    const visibleThinking = after.thinkingRows.filter((r) => r.display !== 'none');
    pinDefectAbsent(visibleThinking.length > 0,
      `重连重建面 thinking 内容可见（${JSON.stringify(after.thinkingRows.map((r) => ({ d: r.display, l: r.labelText })))}）`);
    // done 语义也不允许被重建（busy 重建面只允许 running 态或折叠态）
    for (const row of after.thinkingRows) {
      expect(row.labelExpanded === true ? row.display === 'none' : true).toBeTruthy();
    }
  } finally { await context.close(); }
});

// ── C. 纯思考历史行（症状①历史腿） ────────────────────────────────────
test('C thinking-only 历史行重放后恒折叠', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  try {
    const snap = await page.evaluate(async (history) => {
      await window.__reconnect(history, { thinkBuf: '', txtBuf: '', stillBusy: false });
      return window.__snapshot();
    }, JSON.parse(JSON.stringify(HISTORY_BUSY)));
    const visibleThinking = snap.thinkingRows.filter((r) => r.display !== 'none');
    pinDefectAbsent(visibleThinking.length > 0,
      `thinking-only 历史行重放后内容可见（${visibleThinking.length} 行）`);
  } finally { await context.close(); }
});

// ── D. 工具结果体（守卫钉：基线与本树都该恒折叠） ───────────────────────
test('D 重连后工具卡 body 不自动 .open', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  try {
    const after = await page.evaluate(async (history) => {
      await window.__reconnect(history, { thinkBuf: '', txtBuf: '', stillBusy: false });
      return window.__snapshot();
    }, JSON.parse(JSON.stringify(HISTORY_BUSY)));
    expect(after.toolBodies.length).toBeGreaterThan(0);
    const openBodies = after.toolBodies.filter((b) => b.open || b.display !== 'none');
    pinGuard(openBodies.length > 0,
      `重放后工具卡 body 自动展开（${openBodies.length} 个）`);
  } finally { await context.close(); }
});

// ── E. 抽动阈值（Layout Instability + scrollTop 不跳） ───────────────
test('E 抽动阈值：重连重放全程 CLS 低于阈值；中段视口不跳', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  try {
    await page.evaluate(() => window.__live({ midThinking: true }));
    // 把用户视口钉在中段（模拟「正在回看中段」时的重连）
    await page.evaluate(() => {
      const chat = document.getElementById('chat');
      chat.scrollTop = Math.max(0, chat.scrollHeight / 2 - chat.clientHeight / 2);
    });
    const beforeScroll = await page.evaluate(() => document.getElementById('chat').scrollTop);

    await armShiftObserver(page);
    await page.evaluate(async ({ history, thinkBuf }) => {
      await window.__reconnect(history, { thinkBuf, txtBuf: '', stillBusy: true });
    }, { history: JSON.parse(JSON.stringify(HISTORY_BUSY)), thinkBuf: RECONNECT_THINKING });
    const shift = await readShift(page);
    const afterScroll = await page.evaluate(() => document.getElementById('chat').scrollTop);

    // 阈值：0.05 = Google 的 "needs improvement" 上限的一半——重建几百 px 高的
    // 块一次就会过线；钉的就是「重连不抽动」。CLS 观察器只对**存活元素的位移**
    // 计数，wipe+重建的新元素不产 CLS（实测 0.0000）——CLS 钉是守卫，整块
    // 重挂载的抽动钉由 F/G 的节点身份 + 行矩形钉承载。
    pinGuard(shift.value >= 0.05, `重连重放 CLS ${shift.value.toFixed(4)} ≥ 0.05`);
    // 用户停在中段时，重连不得把视口拉到底（shouldFollowBottom=false 语义）。
    pinGuard(Math.abs(afterScroll - beforeScroll) >= 80,
      `重连把视口拉动了 ${Math.round(afterScroll - beforeScroll)}px（≥80）`);
  } finally { await context.close(); }
});

// ── F. 幂等重放 + footer 零位移 + 折叠态跨重放持久（#570 家族封口） ───
test('F 同帧重复重放：节点身份不变、footer 零位移、用户折叠态存活', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  try {
    await page.evaluate(() => window.__reset());
    // 第一次重放 = 全量渲染（内容落 DOM）
    await page.evaluate(async (history) => {
      await window.__reconnect(history, { thinkBuf: '', txtBuf: '', stillBusy: false });
    }, JSON.parse(JSON.stringify(HISTORY_BUSY)));

    // 用户操作面：点开第一行 thinking（content 显示）+ 环境 settle。展开操作
    // 之后有一个与重放无关的 settle 窗（deferMd 升级批 / 字体度量收尾）——
    // 先静置到位再取身份戳，钉的才是「紧随其后的同帧重放不得再动任何东西」。
    const expandedIdx = await page.evaluate(() => {
      const label = document.querySelector('.thinking-row .thinking-label');
      label.click();
      return 0;
    });
    expect(expandedIdx).toBe(0);
    await page.waitForTimeout(600);

    // 核心单 evaluate：页内同域做节点身份 ===（跨 evaluate 的元素引用会被
    // 序列化，恒不"相等"）。identity = 重放前 stamp → 第二次同帧重放 → 读回。
    const f = await page.evaluate(async (history) => {
      window.__stampIdentity();
      const scrollBefore = document.getElementById('chat').scrollTop;
      await window.__reconnect(history, { thinkBuf: '', txtBuf: '', stillBusy: false });
      const id = window.__readIdentity();
      const rows = [...document.querySelectorAll('.thinking-row')];
      const firstContent = rows[0] && rows[0].querySelector('.thinking-content');
      return {
        ...id,
        scrollTop: document.getElementById('chat').scrollTop,
        scrollBefore,
        firstExpanded: !!(firstContent && firstContent.style.display !== 'none'),
        firstStillConnected: !!(rows[0] && rows[0].isConnected),
      };
    }, JSON.parse(JSON.stringify(HISTORY_BUSY)));

    // ① 零重挂载：footer 胶囊与全行集合是**同一批元素引用**。
    pinDefectAbsent(!f.sameBadge || !f.sameRows || !f.firstStillConnected,
      `footer 胶囊/行集合被重挂载（sameBadge=${f.sameBadge} sameRows=${f.sameRows}）`);
    // ② footer 零位移 + 行集合零重排（矩形逐像素相等）。
    pinDefectAbsent(!f.rectsEqual,
      '行矩形集合漂移（整块重排/footer 跳动）');
    // ③ 行数不变（#570 家族守卫：重复重放不追加任何行）。
    pinGuard(f.childCount !== f.stampedCount,
      `行数变化 ${f.stampedCount} → ${f.childCount}（重复气泡/丢行）`);
    // ④ 视口零劫持（守卫：两向都不得发生）。
    pinGuard(!f.scrollTopEqual || f.scrollTop !== f.scrollBefore,
      `scrollTop 被重放改变 ${f.scrollBefore} → ${f.scrollTop}`);
    // ⑤ 折叠态跨重放持久：用户展开的那行保持展开。
    pinDefectAbsent(!f.firstExpanded,
      '用户展开的 thinking 行被重放折回（折叠态未跨重放持久）');
    // ⑥ 工具卡照旧恒折叠（守卫）。
    const snap2 = await page.evaluate(() => window.__snapshot());
    pinGuard(snap2.toolBodies.some((b) => b.open || b.display !== 'none'),
      '重复重放后工具卡 body 自动展开');
  } finally { await context.close(); }
});

// ── G. CDP 节流 + WS 强制重连循环（三症状全链路复现面） ────────────────
test('G CDP 节流 + WS 强制重连循环 ×3：闪现/抽动/跳动全程不出现', async ({ browser }) => {
  const { context, page, pageErrors } = await newPage(browser);
  try {
    await page.evaluate(() => window.__reset());

    // CDP 节流：250ms 单向时延 + 64KB/s —— 拉长每帧重放的竞态窗口。
    const cdp = await context.newCDPSession(page);
    await cdp.send('Network.enable');
    await cdp.send('Network.emulateNetworkConditions', {
      offline: false,
      latency: 250,
      downloadThroughput: 64 * 1024,
      uploadThroughput: 64 * 1024,
    });

    // 真实 ws.js：connect → 迷你 peer 101 → onopen 引导帧 → onReconnect 腿
    // 发 getHistory → historyPage 重放（main.js 同款初载路由）。
    await page.evaluate(() => window.__wsStart());
    const first = await page.evaluate(() => window.__wsWaitReplay(0, 15000));
    expect(first, '首帧 historyPage 重放未在 15s 内落地').toBe(true);
    await page.waitForTimeout(400); // 首帧重放的 rAF settle

    const pre = await page.evaluate(() => {
      window.__stampIdentity(); // 页内身份戳（跨 evaluate 的元素引用不可比）
      return {
        snap: window.__snapshot(),
        id: window.__nodeIdentity(),
      };
    });

    // 强制重连循环 ×3：每轮 forceReconnect（清退避立即重连）+ 一次完整重放。
    const loop = await page.evaluate(() => window.__wsLoop(3));
    // 首帧 1 次 + 每轮重连各 1 次 = ≥4 次重放；未满 ⇒ 断言暴露（防静默假绿）。
    expect(loop.replays, `强制重连循环重放次数不足：${JSON.stringify(loop)}`).toBeGreaterThanOrEqual(4);

    const post = await page.evaluate(() => ({
      snap: window.__snapshot(),
      id: window.__nodeIdentity(),
      stamp: window.__readIdentity(),
    }));

    // 症状①：thinking（含 thinking-only 历史行）恒折叠。
    pinDefectAbsent(post.snap.thinkingRows.some((r) => r.display !== 'none'),
      `重连循环后 thinking 内容可见（${post.snap.thinkingRows.filter((r) => r.display !== 'none').length} 行）`);
    // 症状② + ③：零重挂载 ⇒ footer 胶囊/行集合/矩形/滚动位全程不动。
    pinDefectAbsent(!post.stamp.sameBadge || !post.stamp.sameRows,
      `重连循环重挂载了 DOM（sameBadge=${post.stamp.sameBadge} sameRows=${post.stamp.sameRows}）`);
    pinDefectAbsent(!post.stamp.rectsEqual,
      '重连循环行矩形集合漂移（footer 跳动/窗口抽动）');
    // 视口守卫：循环期间滚动位不动（短内容下 scrollTop 恒 0 ⇒ 守卫语义）。
    pinGuard(post.id.scrollTop !== pre.id.scrollTop,
      `重连循环劫持了视口 ${pre.id.scrollTop} → ${post.id.scrollTop}`);
    // 循环零 JS 错误（守卫：两向都要求零错误）。
    pinGuard(pageErrors.length > 0, `重连循环页面错误：${pageErrors.join(' | ')}`);
  } finally { await context.close(); }
});

// ── 证据帧（两份，基线/本树各一） ─────────────────────────────────────
test('evidence：重连后 DOM 截图（人工可读的复现帧）', async ({ browser }) => {
  const { context, page } = await newPage(browser);
  try {
    await page.evaluate(async (thinkBuf) => {
      window.__reset();
      window.__live({ midThinking: true });
      await window.__reconnect(
        [
          { type: 'user', text: '帮我看一下文件', timestamp: 1750000000000 },
          { type: 'ai', text: '先读一下入口文件。',
            thinking: '我先梳理一下当前文件的结构：\n\n- 入口在 main.scala\n- 核心逻辑分三层',
            timestamp: 1750000001000 },
        ],
        { thinkBuf, txtBuf: '', stillBusy: true },
      );
    }, RECONNECT_THINKING);
    const tag = process.env.NB_UX_ROOT && process.env.NB_UX_ROOT !== REPO_ROOT
      ? 'baseline' : 'fixed';
    await page.screenshot({
      path: path.join(SHOT_DIR, `20261020-reconnect-collapse-after-${tag}.png`),
      fullPage: true,
    });
  } finally { await context.close(); }
});
