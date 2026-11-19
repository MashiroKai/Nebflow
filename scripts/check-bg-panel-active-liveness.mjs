// check-bg-panel-active-liveness.mjs — 后台智能体面板「活跃行 ↔ 引擎注册表」一致性闸
// （陈旧面板行批 2026-11-18 · 作者报障「面板大量陈旧行标着在跑、时长 690h+」）。
//
// 断言（本闸的唯一判据，机械可读）：
//
//   面板渲染为 **active（进行中）** 的行，其会话必须在**引擎活跃快照**里有对应实例；
//   durable store（SubAgentTaskStore `/api/subagent-tasks/<root>` 投影）里引擎并未报活
//   的档位（`pending-recovery` 重启打断族 / completed / cancelled / failed）**一律不得**
//   渲染成 active。
//
// 为什么必须是一道闸（而不是顺手修的注释）：
//   数据面是**两条**通路并流——① 引擎活跃快照 `activeAgents`（WS，只在飞的 registry
//   行）；② durable store 行投影（REST，**全量**任务记录，跨重启存活）。②里的
//   `pending-recovery`（SubAgentStartupRecovery 标记的重启打断行：执行已停、后端零
//   通知、等人工恢复）在 bgScopeStoreInfo 里被折成 'Processing' ⇒ 状态机末档
//   'active' ⇒ 面板上一条 **29 天龄**（spawnedAt 距今 690h45m）的记录顶着「进行中」
//   徽标 + 「已运行 690h45m」时钟。这正是作者报障的形态。
//
//   判据：状态真相在引擎侧（哪些实例在跑），呈现面只许**照抄**，不许把「记录存在」
//   读成「正在跑」。本闸把这条判据变成可执行断言——破坏实现即红。
//
// 提取方式（与 scripts/tests/subagent-visibility.test.mjs 同法）：从真实源文件切出被闸的
//   函数体进 vm —— 闸吃的是**真代码**，不是复刻件（复刻件会与实现一起漂移）。
//
// 方案 A 批（2026-11-18 · 作者令「行集一字不动，只加一个权威活性位 + 闸」）追加断言⑤：
//   引擎权威活性位 `active` 的**消费面**——位为假的行不画成「进行中」、不挂运行时钟；
//   位为真 / 缺席（旧后端）逐字与今天相同。位由引擎单点派生（WebSocketRoutes.panelRowActivity，
//   与 sessionBusyStates 同轴），呈现面只许照读——本闸的 ①③⑤ 合起来把这条不变量的两侧都钉住。
//
// Run: node scripts/check-bg-panel-active-liveness.mjs    （exit 0 = 绿，1 = 红）

import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const WEB = join(ROOT, 'src', 'main', 'resources', 'web', 'js');
const read = (rel) => readFileSync(join(WEB, rel), 'utf8');

const failures = [];
const ok = (name, detail) => console.log(`  PASS  ${name}${detail ? ' — ' + detail : ''}`);
const bad = (name, detail) => { failures.push(name + (detail ? ' — ' + detail : '')); console.log(`  FAIL  ${name}${detail ? ' — ' + detail : ''}`); };

// ── 真代码切出（不是复刻） ────────────────────────────────────────────────
const bgSrc = read('main/bgAgents.js');
const revisionSrc = read('subagentRevision.js').replace(/export /g, '');

function slice(src, startMarker, endMarker) {
  const start = src.indexOf(startMarker);
  if (start < 0) throw new Error(`gate: marker not found: ${startMarker}`);
  const end = src.indexOf(endMarker, start);
  if (end < 0) throw new Error(`gate: end marker not found: ${endMarker}`);
  return src.slice(start, end);
}

const scopeStoreSrc = slice(bgSrc, 'function bgScopeStoreInfo(', '\n/** Silent-completion');
const rowStateSrc = slice(bgSrc, 'function bgRowState(info) {', '\n// ── #752 空闲 delegate');

const ctx = vm.createContext({
  console, Date, Number, String, Math,
  // 状态机依赖的两个叶子判据（真身分别在 freezeState.js / managePanel.js；本闸只关心
  // pending-recovery 与终态档，故给最小等价替身）。
  isFrozenFamily: () => false,
  isFailedSnapshotStatus: (s) => typeof s === 'string' && s.startsWith('Error'),
});
vm.runInContext(`${revisionSrc}\n${scopeStoreSrc}\n${rowStateSrc}`, ctx);

// 引擎活跃快照（真实在飞实例）——本闸判据的**唯一真源**。
const NOW = Date.now();
const HOUR = 3600_000;
const liveRegistry = new Set(['delegate-live-0001', 'node-live-0002']);

// durable store 行（`/api/subagent-tasks/<root>` 全量投影；跨重启存活）。
const storeRows = [
  { taskId: 'delegate-live-0001', status: 'running', executionStatus: 'running', spawnedAt: NOW - 3 * HOUR, source: 'delegate', description: '在飞批' },
  { taskId: 'node-live-0002', status: 'running', executionStatus: 'running', spawnedAt: NOW - 1 * HOUR, source: 'node', description: '在飞节点' },
  { taskId: 'delegate-stale-8bbd5826', status: 'pending-recovery', executionStatus: null, spawnedAt: NOW - 690 * HOUR, completedAt: NOW - 600 * HOUR, source: 'delegate', description: '实施批 · 本地加速 M2 文件分块腿' },
  { taskId: 'delegate-stale-2b17c0a4', status: 'pending-recovery', executionStatus: null, spawnedAt: NOW - 693 * HOUR, source: 'delegate', description: '修复批 · 搜索消息记录返回空' },
  { taskId: 'delegate-done-0003', status: 'completed', executionStatus: 'finished', spawnedAt: NOW - 700 * HOUR, source: 'delegate', description: '已完成批' },
  { taskId: 'delegate-cancel-0004', status: 'cancelled', executionStatus: 'cancelled', spawnedAt: NOW - 400 * HOUR, source: 'delegate', description: '已取消批' },
  { taskId: 'delegate-failed-0005', status: 'failed', executionStatus: 'failed', spawnedAt: NOW - 400 * HOUR, source: 'delegate', description: '失败批' },
];

const stateOf = (row) => vm.runInContext(
  `bgRowState(bgScopeStoreInfo(${JSON.stringify(row)}, 'root-X'))`, ctx);

console.log('check-bg-panel-active-liveness — 面板活跃行必须能在引擎注册表里找到对应实例');
console.log(`  fixture: live=${liveRegistry.size} / store=${storeRows.length} rows`);

// ── 断言①：活跃行 ⊆ 引擎活跃快照（主判据）──────────────────────────────────
const activeRows = storeRows.filter((r) => stateOf(r) === 'active');
const orphans = activeRows.filter((r) => !liveRegistry.has(r.taskId)).map((r) => r.taskId);
if (orphans.length === 0) {
  ok('活跃行 ⊆ 引擎活跃快照', `${activeRows.length} 条活跃行全部在册（${activeRows.map((r) => r.taskId).join(', ')}）`);
} else {
  bad('活跃行 ⊆ 引擎活跃快照', `渲染为进行中但引擎注册表无对应实例：${orphans.join(', ')}`);
}

// ── 断言②：逐档状态读数（哪一档该出什么字）────────────────────────────────
const EXPECT = {
  'delegate-live-0001': 'active',
  'node-live-0002': 'active',
  'delegate-stale-8bbd5826': 'pending-recovery',
  'delegate-stale-2b17c0a4': 'pending-recovery',
  'delegate-done-0003': 'done',
  'delegate-cancel-0004': 'done',
  'delegate-failed-0005': 'error',
};
for (const row of storeRows) {
  const got = stateOf(row);
  if (got === EXPECT[row.taskId]) ok(`状态档 ${row.taskId}`, `${row.status} → ${got}`);
  else bad(`状态档 ${row.taskId}`, `期望 ${EXPECT[row.taskId]}，实得 ${got}（store status=${row.status}）`);
}

// ── 断言③：待恢复行不得再挂运行时钟（690h+ 形态根除）──────────────────────
const uptimeSrc = slice(bgSrc, 'const notRunning = terminal || rowState ===', '// Retries chip');
if (/notRunning\s*=\s*terminal\s*\|\|\s*rowState\s*===\s*'pending-recovery'/.test(uptimeSrc)) {
  ok('待恢复行不挂运行时钟', 'bgTaskRowHtml 的 uptime 判据含 pending-recovery');
} else {
  bad('待恢复行不挂运行时钟', 'bgTaskRowHtml 未把 pending-recovery 排除出运行时长槽');
}

// ── 断言④：时长算术（真实时刻差；单位/时区无关）───────────────────────────
const manageSrc = read('managePanel.js');
const fmtSrc = slice(manageSrc, 'export function fmtUptime(ms) {', '/** Build the right-side management cluster');
const fmtCtx = vm.createContext({ Math, Number });
vm.runInContext(fmtSrc.replace('export ', ''), fmtCtx);
const fmt = (ms) => vm.runInContext(`fmtUptime(${ms})`, fmtCtx);

const SAMPLES = [
  [0, '0s'],
  [59_000, '59s'],
  [60_000, '1m 0s'],
  [3_599_000, '59m 59s'],
  [3_600_000, '1h 0m'],
  [90 * 60_000 + 30_000, '1h 30m'],
  [26 * HOUR + 30 * 60_000, '26h 30m'],   // 跨天（>24h）
  [690 * HOUR + 45 * 60_000, '690h 45m'], // 报障读数：算术正确，错的是**基数**不是单位
];
let durationOk = true;
for (const [ms, want] of SAMPLES) {
  const got = fmt(ms);
  if (got !== want) { durationOk = false; bad(`时长样例 ${ms}ms`, `期望 ${want}，实得 ${got}`); }
}
if (durationOk) ok('时长样例（秒/分/小时/跨天/690h45m）', `${SAMPLES.length}/${SAMPLES.length}`);

// 跨时区：同一 epoch ms 差在两个时区下读数逐字节相同（纯算术 ⇒ 无时区耦合）。
const TZ_PROBE = "const fs=require('fs');const s=fs.readFileSync(process.argv[1],'utf8');"
  + "const a=s.indexOf('export function fmtUptime(ms) {');"
  + "const b=s.indexOf('/** Build the right-side');"
  + "const body=s.slice(a,b).replace('export ','');"
  + "eval(body + ';process.stdout.write([0,59000,3600000,86400000].map(fmtUptime).join(\"|\"));');";
const fmtUnderTz = (tz) => execFileSync(process.execPath, ['-e', TZ_PROBE, join(WEB, 'managePanel.js')], {
  env: { ...process.env, TZ: tz }, encoding: 'utf8',
});
const tzA = fmtUnderTz('Asia/Shanghai');
const tzB = fmtUnderTz('America/New_York');
if (tzA === tzB && tzA === '0s|59s|1h 0m|24h 0m') ok('跨时区一致（Asia/Shanghai vs America/New_York）', tzA);
else bad('跨时区一致', `Shanghai=${tzA} New_York=${tzB}`);

// ── 断言⑤：引擎权威活性位（方案 A 批 2026-11-18 · 作者令「行集一字不动，只加一个权威
// 活性位 + 闸」）────────────────────────────────────────────────────────────
// 「活跃行 ⊆ 引擎活跃快照」这条不变量的右边必须由**引擎**给：快照行每行随帧带
// `active`（后端 WebSocketRoutes.panelRowActivity 单点 = 注册表在册 ∧ status==Processing
// ∧ 未停靠/未冻结/未重启中断）。本断言把**真代码**的两处消费判据切出来实跑：
//   · bgRowState：位为假 ⇒ 不画成「进行中」（末档 'active' 只留给引擎说在跑的行）；
//     位为真 / 缺席 ⇒ 与今天逐字相同（**不许把「未提供」当 false**）；
//   · bgTaskRowHtml 的 uptime 判据：位为假 ⇒ 不挂运行时钟；位为真 / 缺席 ⇒ 照旧挂。
const rowStateOfInfo = (info) => vm.runInContext(`bgRowState(${JSON.stringify(info)})`, ctx);
const bitCases = [
  ['位为假 ⇒ 不画成在跑', { status: 'Processing', engineActive: false }, 'idle'],
  ['位为真 ⇒ 照旧在跑', { status: 'Processing', engineActive: true }, 'active'],
  ['位缺席（旧后端）⇒ 与今天逐字相同', { status: 'Processing' }, 'active'],
];
for (const [name, info, want] of bitCases) {
  const got = rowStateOfInfo(info);
  if (got === want) ok(`bgRowState·${name}`, `engineActive=${String(info.engineActive)} → ${got}`);
  else bad(`bgRowState·${name}`, `期望 ${want}，实得 ${got}（info=${JSON.stringify(info)}）`);
}

const notRunningExpr = slice(bgSrc, 'const notRunning = ', ';').slice('const notRunning = '.length);
const clockCtx = vm.createContext({});
vm.runInContext(`function hangsClock(terminal, rowState, info) { return !(${notRunningExpr}); }`, clockCtx);
const hangs = (info) => vm.runInContext(`hangsClock(false, 'active', ${JSON.stringify(info)})`, clockCtx);
const clockCases = [
  ['位为假 ⇒ 不挂运行时钟', { engineActive: false }, false],
  ['位为真 ⇒ 照旧挂运行时钟', { engineActive: true }, true],
  ['位缺席（旧后端 / durable store 行）⇒ 与今天逐字相同', {}, true],
];
for (const [name, info, want] of clockCases) {
  const got = hangs(info);
  if (got === want) ok(`运行时钟·${name}`, `engineActive=${String(info.engineActive)} → 挂表=${got}`);
  else bad(`运行时钟·${name}`, `期望 挂表=${want}，实得 ${got}`);
}

console.log(`\ncheck-bg-panel-active-liveness: ${failures.length === 0 ? 'PASS (exit 0)' : `FAIL (exit 1) — ${failures.length} 项`}`);
if (failures.length) { for (const f of failures) console.log('  · ' + f); process.exit(1); }
process.exit(0);
