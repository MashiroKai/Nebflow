// turn-terminal-replay.spec.mjs — 回放折叠「A 根治」实施批回归（2026-10-28，
// 作者拍板案 A；设计依据 reports/20261028_113024_console-replay-compaction-audit 案一）。
//
// 根因（审计 §1.1）：live 与回放是两条折叠路径——回放折叠靠渲染期徽章启发式
// （findDoneBadge 只认 data-nf-phrase，而徽章裹在 persistence.js 的 if (m.text)
// 里）反推「轮已成功」，推导不出 ⇒ 整段平铺 ⇒ 裸工具卡。本批把「轮次结束」
// 变成落盘数据（UiMessage.Ai.terminal=true，done/agentDone 落盘点同批写入），
// 回放 done 判据改「段内存在 terminal 行」优先、旧转录回落现启发式渐进兼容；
// 顺带把徽章渲染从 if (m.text) 提出（「有 durationMs 但 text 为空」的纯工具轮
// 徽章整个消失的独立 bug）。
//
// 判据面 = the RENDERED DOM（真实 restoreFromBackendHistory + turnGroup 链路，
// never the source wording）。十个面：
//   T1 terminal 行折叠（新判据·有 text 有 dur）     — header + 工具 tucked
//   T2 旧转录回落（无 terminal，徽章启发式仍管用）   — 守卫钉（两树同绿）
//   B1 纯工具轮徽章·legacy（text 空 dur>0 无 terminal）— 新树红→绿（独立 bug 修）
//   B2 纯工具轮徽章·terminal（text 空 dur>0 terminal） — 新面（红腿要求缺陷在场）
//   F  失败轮平铺（无 ai 行）                        — 守卫钉（09-05 排障裁定不回归）
//   BT busyTail 尾段 open（尾段无标记）              — 守卫钉（瞬态语义不回归）
//   BT2 busyTail + terminal 尾段闭合                 — 新面（数据面驱动 exchangeComplete）
//   M  terminal 纯文本轮（无可折行，E5）             — 无 header、不崩（两树同形）
//   M2 terminal 空 text 无 dur（model 回落进 header）— 新面（header 仍成立）
//   X  点 header 展开/收起（形态红线：与 live 同形） — 行集原位复原
//
// 静态服务器由本 spec 自起自灭（随机高位端口，host :8080 永不触碰）。
//
// 跑法：
//   node <main-repo>/node_modules/@playwright/test/cli.js test tests/turn-terminal-replay.spec.mjs
// 红腿（对修复前树，never checkout main；红绿双向全绿 = 修复面证据）：
//   mkdir -p /tmp/nb-turn-terminal-base
//   git -C <repo> archive main src/main/resources/web | tar -x -C /tmp/nb-turn-terminal-base
//   NB_UX_ROOT=/tmp/nb-turn-terminal-base PIN_EXPECT=red node ... test tests/turn-terminal-replay.spec.mjs

import { test, expect } from '@playwright/test';
import http from 'node:http';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/turn-terminal-replay/harness.html';
const HARNESS_FILE = path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, ''));
const SHOT_DIR = process.env.NB_UX_SHOT_DIR || path.join(os.homedir(), '.nebflow', 'docs', 'Nebflow');

// 被测树 = NB_UX_ROOT（红腿基线树）或本 worktree（绿腿）。
const SERVE_ROOT = process.env.NB_UX_ROOT || REPO_ROOT;
const RED = process.env.PIN_EXPECT === 'red';

/** 新面钉：绿腿要求新行为在场；红腿（PIN_EXPECT=red）要求缺陷在场——同一
 *  命令双向全绿，红腿输出即复现证据。 */
function pinNewFace(holds, desc) {
  if (RED) expect(holds, `红腿要求缺陷在场：${desc}`).toBe(false);
  else expect(holds, `绿腿要求新行为在场：${desc}`).toBe(true);
}

/** 守卫钉：两棵树上都必须成立（不随 PIN_EXPECT 反转）。 */
function pinGuard(holds, desc) {
  expect(holds, `守卫钉（${RED ? '红腿' : '绿腿'}）：${desc}`).toBe(true);
}

// ---- fixtures（SessionRecorder/WebSocketRoutes 落盘口径的 ai/tool/user 行）----
const T = 1750000000000;
const user = (text, ts = T) => ({ type: 'user', text, timestamp: ts });
const tool = (label, summary, ts) => ({ type: 'tool', label, summary, content: 'ok', isError: false, input: {}, ...(ts ? {} : {}) });

const TERMINAL_TEXT_TURN = [
  user('跑个任务'),
  tool('Bash', 'ls -la'),
  tool('Read', 'a.scala'),
  { type: 'ai', text: '任务完成。', durationMs: 61000, model: 'glm-5.3', timestamp: T + 61000, terminal: true },
];
const LEGACY_TEXT_TURN = TERMINAL_TEXT_TURN.map(({ terminal, ...m }) => m); // 旧转录：无 terminal 键
const PURE_TOOL_LEGACY = [
  user('纯工具轮（旧转录）'),
  tool('Bash', 'deploy.sh'),
  { type: 'ai', text: '', durationMs: 8000, model: 'glm-5.3', timestamp: T + 8000 },
];
const PURE_TOOL_TERMINAL = [
  user('纯工具轮（新写者）'),
  tool('Bash', 'deploy.sh'),
  { type: 'ai', text: '', durationMs: 8000, model: 'glm-5.3', timestamp: T + 8000, terminal: true },
];
const FAILED_TURN = [
  user('会失败的轮'),
  tool('Bash', 'boom', void 0),
];
FAILED_TURN[1].isError = true;
const BUSY_TAIL_TURN = [
  user('进行中的轮'),
  tool('Grep', 'pattern'),
  { type: 'ai', text: '想一半。', timestamp: T + 3000 }, // 无 dur 无 terminal
];
const BUSY_TAIL_TERMINAL = [
  user('断线前已完成的轮'),
  tool('Grep', 'pattern'),
  // 无时长 —— terminal 是段内唯一完成判据（有 dur 的尾段在旧启发式下也闭合，
  // 不足以钉新面）。
  { type: 'ai', text: '做完了。', timestamp: T + 5000, terminal: true },
];
const TERMINAL_TEXTLESS_NODUR = [
  user('空 text 终态（无时长）'),
  tool('Mail', '→ delegate'),
  { type: 'ai', text: '', model: 'm-nine', timestamp: T + 9000, terminal: true },
];
const TERMINAL_PURE_TEXT = [
  user('纯文本轮'),
  { type: 'ai', text: '只有一句话。', timestamp: T + 1200, terminal: true },
];
// ── 卫生批（2026-10-28，回放残余①根治）：退化全工具轮的等价终态面 ──
// 段内零 ai 行（无任何 text/thinking 载体）——回放批 C 相位唯一残余 1 段 1 卡的
// 同款形态（user 行 + 单张 Mail 委托卡）。终态盖到段内最后一行 Tool 行。
const DEGENERATE_TOOL_TERMINAL = [
  user('把结论发给 delegate 续跑'),
  { type: 'tool', label: 'Mail', summary: '→ delegate-nebflow-…（结论交接）', content: 'ok', isError: false, input: {}, terminal: true },
];
const DEGENERATE_TOOL_LEGACY = DEGENERATE_TOOL_TERMINAL.map(({ terminal, ...m }) => m); // 旧转录：无 terminal 键
const TOOL_TAIL_BUSY_TERMINAL = [
  user('前一轮（已闭合）'),
  { type: 'ai', text: '上一轮完成。', durationMs: 5000, timestamp: T + 5000, terminal: true },
  user('全工具收尾轮（断线时已 done）'),
  { type: 'tool', label: 'Bash', summary: 'systemctl status', content: 'ok', isError: false, input: {}, terminal: true },
];

let port;
let srv;

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.listen(0, '127.0.0.1', () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
    s.on('error', reject);
  });
}

function startStaticServer(port, dir) {
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
  return srv;
}

test.beforeAll(async () => {
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  // 红腿引导：基线树只导出了 src/main/resources/web，harness 不在其内 ⇒ 把本树
  // 的 harness 覆盖复制过去（reconnect-collapse / history-replay-cards 同款自举；
  // 覆盖 = /tmp 里的陈年副本不会让新探针静默失效）。
  const serveHarness = path.join(SERVE_ROOT, HARNESS_PATH.replace(/^\//, ''));
  if (path.resolve(serveHarness) !== path.resolve(HARNESS_FILE)) {
    fs.mkdirSync(path.dirname(serveHarness), { recursive: true });
    fs.copyFileSync(HARNESS_FILE, serveHarness);
  }
  expect(fs.existsSync(serveHarness)).toBe(true);
  port = await freePort();
  srv = startStaticServer(port, path.resolve(SERVE_ROOT));
  expect(port).not.toBe(8080); // host discipline: never touch the host port
});

test.afterAll(async () => {
  if (srv) await new Promise(r => srv.close(r));
});

async function replay(page, msgs, opts = {}) {
  await page.goto(`http://127.0.0.1:${port}${HARNESS_PATH}`);
  await page.waitForFunction(() => window.__ready === true);
  return page.evaluate(([m, o]) => window.__replay(m, o), [msgs, opts]);
}

test('T1 terminal 行折叠：header + 工具 tucked（数据面判据）', async ({ page }) => {
  const c = await replay(page, TERMINAL_TEXT_TURN);
  pinGuard(c.headers === 1, `恰一枚常驻 turn-header（got ${c.headers}）`);
  pinGuard(c.tools === 2 && c.toolsTucked === 2, '两张工具卡全部 tucked');
  pinGuard(c.headerStates[0] === 'done', 'header 处于 done 态（可点开）');
  pinGuard(c.headerTexts[0].includes('glm-5.3'), 'header 摘要带模型名');
  pinNewFace(c.terminalStamped === 1, `terminal 行标在场（数据面，got ${c.terminalStamped}）`);
});

test('T2 旧转录回落：无 terminal 键仍由徽章启发式折叠（渐进兼容守卫）', async ({ page }) => {
  const c = await replay(page, LEGACY_TEXT_TURN);
  pinGuard(c.headers === 1, `旧转录段落仍折叠（got ${c.headers}）`);
  pinGuard(c.tools === 2 && c.toolsTucked === 2, '旧转录工具卡 tucked');
  pinGuard(c.terminalStamped === 0, '旧转录行无 terminal 标（回落路径）');
});

test('B1 纯工具轮徽章·legacy：空 text 有 dur 的旧行渲染徽章行并折叠（独立 bug 修）', async ({ page }) => {
  const c = await replay(page, PURE_TOOL_LEGACY);
  pinNewFace(c.aiRows === 1 && c.bareBadgeRows === 1, `空 text 终态行渲染无气泡纯徽章行（got aiRows=${c.aiRows}, badgeRows=${c.bareBadgeRows}）`);
  pinNewFace(c.headers === 1, `徽章判据恢复 → 该轮折叠（got headers=${c.headers}）`);
  pinNewFace(c.tools === 1 && c.toolsTucked === 1, '裸工具卡被 tucked');
});

test('B2 纯工具轮徽章·terminal：terminal 行无条件渲染徽章并折叠', async ({ page }) => {
  const c = await replay(page, PURE_TOOL_TERMINAL);
  pinNewFace(c.aiRows === 1 && c.terminalStamped === 1, 'terminal 空行渲染 + 行标在场');
  pinNewFace(c.bareBadgeRows === 1, '徽章行在场（phrase 载体）');
  pinNewFace(c.headers === 1 && c.toolsTucked === 1, '该轮折叠、工具 tucked');
});

test('F 失败轮平铺：无 ai 行的段落无 header、工具卡可见（09-05 排障裁定不回归）', async ({ page }) => {
  const c = await replay(page, FAILED_TURN);
  pinGuard(c.headers === 0, `失败轮无 header（got ${c.headers}）`);
  pinGuard(c.tools === 1 && c.toolsTucked === 0, '失败轮工具卡保持可见（平铺）');
});

test('BT busyTail 尾段 open：无标记尾段不折叠（瞬态语义不回归）', async ({ page }) => {
  const c = await replay(page, BUSY_TAIL_TURN, { busyTail: true });
  pinGuard(c.headers === 0, `进行中尾段无 header（got ${c.headers}）`);
  pinGuard(c.tools === 1 && c.toolsTucked === 0, '尾段工具卡保持可见');
});

test('BT2 busyTail + terminal 尾段：数据面完成判据闭合尾段', async ({ page }) => {
  const c = await replay(page, BUSY_TAIL_TERMINAL, { busyTail: true });
  pinNewFace(c.headers === 1, `terminal 尾段在 busyTail 下仍折叠（got headers=${c.headers}）`);
  pinNewFace(c.tools === 1 && c.toolsTucked === 1, '尾段工具卡 tucked');
});

test('M terminal 纯文本轮：无可折行 → 无 header、行照常渲染（E5 同形）', async ({ page }) => {
  const c = await replay(page, TERMINAL_PURE_TEXT);
  pinGuard(c.headers === 0, `纯文本轮无需 header（got ${c.headers}）`);
  pinGuard(c.aiRows === 1, '纯文本行照常渲染');
  pinNewFace(c.terminalStamped === 1, `terminal 行标在场（got ${c.terminalStamped}）`);
});

test('M2 terminal 空 text 无 dur：header 仍成立，模型名回落进摘要', async ({ page }) => {
  const c = await replay(page, TERMINAL_TEXTLESS_NODUR);
  pinNewFace(c.aiRows === 1 && c.terminalStamped === 1, '无时长 terminal 空行照常渲染');
  pinNewFace(c.headers === 1, `terminal 判据在场 → header 成立（got headers=${c.headers}）`);
  pinNewFace(c.tools === 1 && c.toolsTucked === 1, '工具卡 tucked');
  pinNewFace((c.headerTexts[0] || '').includes('m-nine'), 'header 摘要回落行标模型名');
});

test('X 形态红线：点 header 展开 → 工具卡原位可见；再点收起复原（与 live 同形）', async ({ page }) => {
  await replay(page, TERMINAL_TEXT_TURN);
  pinGuard(await page.locator('#chat > .row.tool').count() === 2, '两张工具卡在场');
  await page.click('#chat > .turn-header');
  let c = await page.evaluate(() => window.__counts());
  pinGuard(c.toolsTucked === 0, '展开后工具卡全部可见（原位，不搬移）');
  pinGuard(c.headers === 1, 'header 常驻不消失');
  await page.click('#chat > .turn-header');
  c = await page.evaluate(() => window.__counts());
  pinGuard(c.toolsTucked === 2, '再点收起：工具卡重新 tucked');
  pinGuard(c.headers === 1, 'header 仍常驻');
});

// ── 卫生批（2026-10-28）工具行等价终态面（回放残余①：段内零 ai 行的退化全工具轮）──

test('TO1 退化全工具轮·terminal 工具行：末张裸卡归零（header + 工具 tucked）', async ({ page }) => {
  const c = await replay(page, DEGENERATE_TOOL_TERMINAL);
  pinNewFace(c.toolTerminalStamped === 1, `terminal 工具行标在场（got ${c.toolTerminalStamped}）`);
  pinNewFace(c.headers === 1, `等价终态判据 → 该轮折叠（got headers=${c.headers}）`);
  pinNewFace(c.tools === 1 && c.toolsTucked === 1, '最后一张裸工具卡被 tucked（归零）');
  pinGuard(c.aiRows === 0, '段内零 ai 行（退化形态本身——两树同形的形状事实）');
  pinGuard(
    c.headers === 0 || (c.headerTexts[0] || '').includes('1'),
    'header 摘要带工具计数段（工具 1 次 / 1 tool call；红腿无 header 时本读数空转）'
  );
});

test('TO2 退化全工具轮·legacy（无 terminal 键）：维持平铺（E6 守卫不回归）', async ({ page }) => {
  const c = await replay(page, DEGENERATE_TOOL_LEGACY);
  pinGuard(c.headers === 0, `旧转录无判据 ⇒ 无 header（got ${c.headers}）`);
  pinGuard(c.toolTerminalStamped === 0, '旧行无 terminal 标');
  pinGuard(c.tools === 1 && c.toolsTucked === 0, '裸工具卡保持可见（平铺，启发式时代现状）');
});

test('TO3 busyTail + terminal 工具尾段：数据面完成判据闭合（exchangeComplete 同读一戳）', async ({ page }) => {
  const c = await replay(page, TOOL_TAIL_BUSY_TERMINAL, { busyTail: true });
  pinNewFace(c.headers === 1, `terminal 工具尾段在 busyTail 下闭合（got headers=${c.headers}）`);
  pinNewFace(c.toolTerminalStamped === 1, '工具尾段行标在场');
  pinNewFace(c.tools === 1 && c.toolsTucked === 1, '尾段工具卡 tucked');
});

test('TO4 形态红线：TO1 的 header 点开可展（与 live 同形）', async ({ page }) => {
  await replay(page, DEGENERATE_TOOL_TERMINAL);
  if (RED) {
    // 红腿：折叠未发生 ⇒ 无 header 可点——「header 缺席」本身就是缺陷在场的证据，
    // 点击腿无从跑起（本面 presuppose TO1 的折叠）。
    pinNewFace(await page.locator('#chat > .turn-header').count() === 1, 'TO1 折叠在场才有 header 可点');
    return;
  }
  pinGuard(await page.locator('#chat > .row.tool').count() === 1, '工具卡在场');
  await page.click('#chat > .turn-header');
  let c = await page.evaluate(() => window.__counts());
  pinGuard(c.toolsTucked === 0, '展开后工具卡可见（原位）');
  await page.click('#chat > .turn-header');
  c = await page.evaluate(() => window.__counts());
  pinGuard(c.toolsTucked === 1, '再点收起：复原');
  pinGuard(c.headers === 1, 'header 常驻');
});
