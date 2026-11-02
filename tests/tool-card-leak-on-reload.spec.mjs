// tool-card-leak-on-reload.spec.mjs — 刷新回放「回合内插话切段 ⇒ 整段裸工具卡」
// 回归批（2026-11-02，作者报障原文：「你看，这工具结果又漏出来了，每次就是如果你
// 在回复中，然后我网络中断或者刷新了页面。再次进来的时候就会看到你的完整的工具
// 执行过程。」）。
//
// 根因（本批排查结论）：回放折叠的**段边界判据** `turnGroup.isNonInjectedUserRow`
// 把「任何非注入 user 行」当轮起点，而后端两个普通用户帧落盘点
// （`WebSocketRoutes.dispatchUserText` / typeless 用户帧腿）与引擎回合状态无关地
// 无条件落一条 User 行 ⇒ 回合内插话把**同一个引擎回合**劈成两段，前段零 terminal
// 载体 ⇒ `summarizeSegment` 平铺 ⇒ 整段工具卡裸奔。断网/刷新只是让这条被 hidden
// 的 tucked 行显形（不是「turn 未结束」—— fixture 里那轮 terminal 正常落盘）。
//
// 判据面 = the RENDERED DOM（真实 restoreFromBackendHistory + turnGroup 链路）。
// fixture = 真实 session d7c3cb54 的 74 行片段（turn opener 16:54:28 → 插话
// 17:05:45 → terminal 17:13:14 dur=1192722ms，71 张工具卡），落盘件：
//   tests/fixtures/tool-card-leak-on-reload/fixture.json         （写者面：插话行 midTurn=true）
//   tests/fixtures/tool-card-leak-on-reload/fixture-legacy.json  （旧转录面：无 midTurn 键）
//
// 跑法：
//   node <repo>/node_modules/@playwright/test/cli.js test tests/tool-card-leak-on-reload.spec.mjs
// 红腿（对修复前树；never checkout main；红绿双向全绿 = 修复面证据）：
//   mkdir -p /tmp/nb-toolcard-base
//   git -C <repo> archive main src/main/resources/web | tar -x -C /tmp/nb-toolcard-base
//   NB_UX_ROOT=/tmp/nb-toolcard-base PIN_EXPECT=red node ... test tests/tool-card-leak-on-reload.spec.mjs

import { test, expect } from '@playwright/test';
import http from 'node:http';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const HARNESS_PATH = '/tests/fixtures/tool-card-leak-on-reload/harness.html';
const HARNESS_FILE = path.join(REPO_ROOT, HARNESS_PATH.replace(/^\//, ''));
const FIXTURE_DIR = path.join(REPO_ROOT, 'tests/fixtures/tool-card-leak-on-reload');
const FIX = JSON.parse(fs.readFileSync(path.join(FIXTURE_DIR, 'fixture.json'), 'utf8'));
const FIX_LEGACY = JSON.parse(fs.readFileSync(path.join(FIXTURE_DIR, 'fixture-legacy.json'), 'utf8'));

// 被测树 = NB_UX_ROOT（红腿基线树）或本 worktree（绿腿）。
const SERVE_ROOT = process.env.NB_UX_ROOT || REPO_ROOT;
const RED = process.env.PIN_EXPECT === 'red';

const TOOL_N = FIX.filter(m => m.type === 'tool').length;          // 71
const INTERJECT = 44;                                             // 插话行索引
const TERMINAL = 73;                                              // terminal 行索引

/** 新面钉：绿腿要求新行为在场；红腿（PIN_EXPECT=red）要求缺陷在场。 */
function pinNewFace(holds, desc) {
  if (RED) expect(holds, `红腿要求缺陷在场：${desc}`).toBe(false);
  else expect(holds, `绿腿要求新行为在场：${desc}`).toBe(true);
}
/** 守卫钉：两棵树上都必须成立。 */
function pinGuard(holds, desc) {
  expect(holds, `守卫钉（${RED ? '红腿' : '绿腿'}）：${desc}`).toBe(true);
}

let port;
let srv;

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); });
    s.on('error', reject);
  });
}

function startStaticServer(p, dir) {
  const MIME = {
    '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
    '.mjs': 'text/javascript; charset=utf-8', '.json': 'application/json',
    '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png',
  };
  const s = http.createServer((req, res) => {
    let u = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    if (u.endsWith('/')) u += 'index.html';
    const file = path.join(dir, u);
    if (!file.startsWith(dir)) { res.writeHead(403); res.end(); return; }
    try {
      const b = fs.readFileSync(file);
      res.writeHead(200, { 'Content-Type': MIME[file.slice(file.lastIndexOf('.')).toLowerCase()] || 'application/octet-stream' });
      res.end(b);
    } catch { res.writeHead(404); res.end('not found'); }
  });
  s.listen(p, '127.0.0.1');
  return s;
}

test.beforeAll(async () => {
  // 红腿自举：基线树只导出 src/main/resources/web ⇒ 把本树的 harness + fixtures
  // 覆盖复制过去（turn-terminal-replay 同款；覆盖 = /tmp 陈年副本不会静默失效）。
  const serveHarness = path.join(SERVE_ROOT, HARNESS_PATH.replace(/^\//, ''));
  if (path.resolve(serveHarness) !== path.resolve(HARNESS_FILE)) {
    fs.mkdirSync(path.dirname(serveHarness), { recursive: true });
    fs.copyFileSync(HARNESS_FILE, serveHarness);
    const fx = path.join(SERVE_ROOT, 'tests/fixtures/tool-card-leak-on-reload');
    fs.mkdirSync(fx, { recursive: true });
    for (const f of ['fixture.json', 'fixture-legacy.json']) {
      fs.copyFileSync(path.join(FIXTURE_DIR, f), path.join(fx, f));
    }
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

// ── L1 真实片段（写者面：后端 midTurn=true）──
test('L1 真实 session 片段：71 张工具卡全部归组折叠（一枚 header，零裸卡）', async ({ page }) => {
  const c = await replay(page, FIX);
  pinGuard(c.tools === TOOL_N, `fixture 工具卡总数 = ${TOOL_N}（got ${c.tools}）`);
  pinNewFace(c.midTurnStamped === 1, `插话行带 data-nf-mid-turn（got ${c.midTurnStamped}）`);
  // 缺陷面（修复前树实测）：headers=1 但只辖 28 张卡、43 张裸卡 —— 所以 header 数量
  // **不是**判据（插话后的那一段照样出 header），判据是「归组覆盖面」。
  pinGuard(c.headers === 1, `整段恰一枚 turn-header（got headers=${c.headers}）`);
  pinNewFace(c.toolsTucked === TOOL_N, `全部 ${TOOL_N} 张工具卡 tucked（got ${c.toolsTucked}；修复前 28）`);
  pinNewFace(c.bareTools === 0, `零张工具卡保持顶层散行（无 header 归属；got ${c.bareTools}；修复前 43）`);
  pinNewFace(c.headerTexts.some(t => t.includes(`工具 ${TOOL_N} 次`)),
    `header 计数覆盖整轮 ${TOOL_N} 张卡（got ${JSON.stringify(c.headerTexts)}；修复前「工具 28 次」）`);
  pinGuard(c.terminalStamped === 1, 'terminal 行标在场（数据面判据，守卫钉）');
});

// ── L2 真实片段（旧转录面：无 midTurn 键）——零迁移降级 ──
test('L2 旧转录片段（无 midTurn 键）：时间戳蕴含推断同样归组，不崩不劣化', async ({ page }) => {
  const c = await replay(page, FIX_LEGACY);
  pinGuard(c.tools === TOOL_N, `fixture 工具卡总数 = ${TOOL_N}（got ${c.tools}）`);
  pinNewFace(c.midTurnInferred === 1, `旧行由时间戳蕴含推断补标（got ${c.midTurnInferred}；修复前 0）`);
  pinGuard(c.headers === 1, `旧转录同样整段一枚 header（got headers=${c.headers}）`);
  pinNewFace(c.bareTools === 0, `旧转录零裸卡（got ${c.bareTools}；修复前 43）`);
  pinNewFace(c.toolsTucked === TOOL_N, `旧转录 ${TOOL_N} 张卡全折（got ${c.toolsTucked}；修复前 28）`);
});

// ── L3 形态红线：header 点开 = 工具卡原位可见；再点收起复原 ──
test('L3 形态红线：header 点开可展 71 张工具卡（原位，不搬移），再点收起复原', async ({ page }) => {
  await replay(page, FIX);
  if (RED) {
    // 缺陷面：那一枚 header 只辖插话之后的一段；插话之前的卡始终裸着、点不开。
    const before = await page.evaluate(() => window.__counts());
    pinNewFace(before.bareTools === 0, `红腿：缺陷在场 ⇒ 有裸卡不属任何 header（got ${before.bareTools}）`);
    await page.click('#chat > .turn-header');
    const after = await page.evaluate(() => window.__counts());
    pinNewFace(after.toolsTucked === TOOL_N, `红腿：点开也展不全 ${TOOL_N} 张（got ${after.toolsTucked}）`);
    return;
  }
  pinGuard(await page.locator('#chat > .row.tool').count() === TOOL_N, `${TOOL_N} 张工具卡在场`);
  await page.click('#chat > .turn-header');
  let c = await page.evaluate(() => window.__counts());
  pinGuard(c.toolsTucked === 0, '展开后工具卡全部可见');
  pinGuard(c.headers === 1, 'header 常驻不消失');
  await page.click('#chat > .turn-header');
  c = await page.evaluate(() => window.__counts());
  pinGuard(c.toolsTucked === TOOL_N, '再点收起：全部重新 tucked');
  pinGuard(c.headers === 1, 'header 仍常驻');
});

// ── L4 真·开轮用户行不受影响（守卫钉：段边界判据只排除插话行）──
test('L4 真·开轮 user 行仍是轮边界（相邻两轮各一枚 header）', async ({ page }) => {
  const T = 1750000000000;
  const msgs = [
    { type: 'user', text: '第一轮', timestamp: T },
    { type: 'tool', label: 'Bash', summary: 'ls', content: 'ok', isError: false },
    { type: 'ai', text: '第一轮完成。', durationMs: 6000, model: 'm', timestamp: T + 6000, terminal: true },
    { type: 'user', text: '第二轮', timestamp: T + 60000 },
    { type: 'tool', label: 'Bash', summary: 'pwd', content: 'ok', isError: false },
    { type: 'ai', text: '第二轮完成。', durationMs: 5000, model: 'm', timestamp: T + 65000, terminal: true },
  ];
  const c = await replay(page, msgs);
  pinGuard(c.headers === 2, `两轮各一枚 header（got ${c.headers}）`);
  pinGuard(c.toolsTucked === 2, '两轮工具卡各自 tucked');
  pinGuard(c.midTurnStamped === 0, '普通用户行不被标成插话（守卫钉）');
});

// ── L5 推不出 ⇒ 维持现状（不许劣化）：无 durationMs 的失败/进行中段仍然平铺 ──
test('L5 判据缺失（无 terminal / 无 durationMs）：维持平铺，不误折', async ({ page }) => {
  const T = 1750000000000;
  const failed = [
    { type: 'user', text: '会失败的轮', timestamp: T },
    { type: 'tool', label: 'Bash', summary: 'boom', content: '', isError: true },
    { type: 'user', text: '插话（无终态可推断）', timestamp: T + 3000 },
    { type: 'tool', label: 'Bash', summary: 'boom2', content: '', isError: true },
  ];
  const c = await replay(page, failed);
  pinGuard(c.headers === 0, `无终态 ⇒ 无 header（got ${c.headers}）`);
  pinGuard(c.toolsTucked === 0, '工具卡保持可见（排障平铺，09-05 裁定不回归）');
  pinGuard(c.midTurnStamped === 0 && c.midTurnInferred === 0, '推不出就不标（不许猜）');
});

// ── L6 busyTail 守卫：进行中尾段仍 open（瞬态语义不回归）──
test('L6 busyTail：末段无 terminal 时不折叠（瞬态语义不回归）', async ({ page }) => {
  const T = 1750000000000;
  const msgs = [
    { type: 'user', text: '断线时在跑的轮', timestamp: T },
    { type: 'tool', label: 'Grep', summary: 'pattern', content: 'x', isError: false },
    { type: 'ai', text: '想一半。', timestamp: T + 3000 },
  ];
  const c = await replay(page, msgs, { busyTail: true });
  pinGuard(c.headers === 0, `在跑尾段无 header（got ${c.headers}）`);
  pinGuard(c.toolsTucked === 0, '在跑尾段工具卡保持可见');
});

