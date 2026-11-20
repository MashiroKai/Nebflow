#!/usr/bin/env node
// check-boot-revival-manual.mjs — boot 复活腿「只留手动腿」闸
// （裁定落地批，作者令 2026-11-19 10:39 逐字：「boot 还是手动吧」；
//  延续 2026-11-06 20:36 作者令「由于重启/软件崩溃导致的错误，不用向上通知，
//  不自动恢复，保存状态后，由用户手动恢复，粒度可以是逐个点击，也可以是子
//  agent 面板一键全部恢复」）。
//
// ── 为什么要有这条闸 ──────────────────────────────────────────────────────
// 「自动复活腿不启用 / 手动腿保留可用」今天**由缺省值承载**：GatewayMain 的四个
// boot 调用点一律传 `manual = Defaults.RestartRecoveryManual`，而该 Defaults
// 缺省为 `"true"`（手动）。行为层已被 ManualRevivalSpec（MR1 手动不复活/零通知、
// MR3 手动能复活、MR7 缺省为 true）钉住，但**源码接线**没有被任何闸看住：
//   - 把任一调用点写死成 `manual = false`（或 `true`）⇒ 行为 spec 仍可能全绿；
//   - 把 Defaults 缺省改成 `"false"` ⇒ 「自动复活腿」被静默重新打开。
// 本闸把这四个调用点的接线 + 两个 kill-switch 的缺省字面量 + 手动腿的存在性
// 全部做成可执行判据；改回开启（自动）即红。
//
// ── Judgement table（整条闸）───────────────────────────────────────────────
//   T1  Scan face   = 六个文件必须在场（GatewayMain / SubAgentStartupRecovery /
//        ProjectCrashRecovery / Defaults / ManualRevival / ManualRevivalHost /
//        PresenceRoutes）。
//   T2  Boot wiring = GatewayMain 里 `manual = nebflow.shared.Defaults.RestartRecoveryManual`
//        恰 4 处（四个 boot 调用点：subagentCrashSweep / projectCrashSweep /
//        delegateScopeCrashSweep / startupRemoteRecovery），且**代码行**里
//        零 `manual = true|false` 字面量（写死 = 绕过开关 ⇒ 红）。
//   T3  Defaults    = `RestartRecoveryManual` 读 `nebflow.startup.recovery.manual`、
//        缺省字面量 `"true"`；`DelegateRevivalEnabled` 缺省 `"true"`（kill-switch 在场）。
//        （改回开启 = 把 `"true"` 改成 `"false"` ⇒ 红。）
//   T4  Manual-first fork = SubAgentStartupRecovery.sweep 的 `candidates.nonEmpty && manual`
//        手动分支在场；标注态 `PendingRecoveryStatus` 在 sweep 的 manual 腿被写；
//        自动复活的唯一调用 `DelegateRevival.reviveAll(` 仍在场（**代码保留**，
//        仅被 manual 开关旁路——不是删除）。
//   T5  Zero notify = 收殓腿的零通知回执字面量在场（`suppressed:zero-notify-by-author-order`
//        与 `parent NOT notified (zero-notify by author order`）。
//   T6  Manual leg usable = ManualRevival.restoreOne/restoreAll 在场；boot 装配
//        `ManualRevivalHost.install(` 在场；两个 REST 臂
//        `Root / "delegates" / "restore"` 与 `"restore-all"` 在场。
//   T7  Probe-before-trust（负控）= 每条判据正则必须命中一份**合成正样本**；且
//        「接线」判据必须**拒绝**一份合成**改回开启**样本（`manual = false` ×4）
//        —— 证明本闸对「改回开启」确实会红（死判据自由此可见）。
//   T8  Spec face   = 行为契约仍在测试面声明（ManualRevivalSpec 的 MR1/MR3/MR7 +
//        DelegateRevivalSpec 的 V1 标题在场；缺场景 = 静默删测）。
//
// ── 用法 ────────────────────────────────────────────────────────────────
//   node scripts/check-boot-revival-manual.mjs                # 0 绿 / 1 红 / 2 环境错误
//   node scripts/check-boot-revival-manual.mjs --json         # 机器读
//   node scripts/check-boot-revival-manual.mjs --root <dir>   # 扫另一棵树（负控用镜像）

import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const DEFAULT_ROOT = fileURLToPath(new URL('..', import.meta.url));
const ARGV = process.argv.slice(2);
const JSON_OUT = ARGV.includes('--json');
const rootIndex = ARGV.indexOf('--root');
const ROOT = rootIndex >= 0 && ARGV[rootIndex + 1] ? ARGV[rootIndex + 1] : DEFAULT_ROOT;

const FILES = {
  gateway: 'src/main/scala/nebflow/gateway/GatewayMain.scala',
  sweep: 'src/main/scala/nebflow/agent/SubAgentStartupRecovery.scala',
  crashRecovery: 'src/main/scala/nebflow/core/project/ProjectCrashRecovery.scala',
  defaults: 'src/main/scala/nebflow/shared/Defaults.scala',
  manualRevival: 'src/main/scala/nebflow/agent/ManualRevival.scala',
  manualHost: 'src/main/scala/nebflow/gateway/ManualRevivalHost.scala',
  routes: 'src/main/scala/nebflow/gateway/PresenceRoutes.scala',
  manualSpec: 'src/test/scala/nebflow/agent/ManualRevivalSpec.scala',
  revivalSpec: 'src/test/scala/nebflow/agent/DelegateRevivalSpec.scala',
};

// ── 判据正则（T7 逐条配合成正样本；负控另见 T7b）─────────────────────────────
const RE_LEG_MANUAL = /manual = nebflow\.shared\.Defaults\.RestartRecoveryManual/g;
const RE_EXPLICIT_MANUAL = /manual\s*=\s*(true|false)\b/;
const RE_DEFAULT_MANUAL =
  /def RestartRecoveryManual: Boolean =\s*\n\s*sys\.props\.getOrElse\("nebflow\.startup\.recovery\.manual", "true"\)/;
const RE_DEFAULT_REVIVAL_KILL =
  /def DelegateRevivalEnabled: Boolean =\s*\n\s*sys\.props\.getOrElse\("nebflow\.delegate\.revival\.enabled", "true"\)/;
const RE_MANUAL_FORK = /case Right\(candidates\) if candidates\.nonEmpty && manual =>/;
const RE_SWEEP_MANUAL_HELPER = /def sweepManualUnprotected\(/;
// 换行不敏感：全仓格式化批（2026-11-21）把实参折行，判据改为 \s* 允许换行，
// 否则闸红的是换行位置而不是契约（实测：scalafmt 把 task, PendingRecoveryStatus, 折成三行）。
const RE_PENDING_STATUS = /transitionObserved\(\s*(task|t)\s*,\s*PendingRecoveryStatus\s*,/;
const RE_AUTO_REVIVE_CALL = /DelegateRevival\.reviveAll\(/;
const RE_ZERO_NOTIFY_RECEIPT = /suppressed:zero-notify-by-author-order/;
const RE_ZERO_NOTIFY_COPY = /parent NOT notified \(zero-notify by author order/;
const RE_RESTORE_ONE = /def restoreOne\(/;
const RE_RESTORE_ALL = /def restoreAll\(/;
const RE_HOST_INSTALL = /ManualRevivalHost\.install\(/;
const RE_ROUTE_ONE = /Root \/ "delegates" \/ "restore"/;
const RE_ROUTE_ALL = /Root \/ "delegates" \/ "restore-all"/;

// ── T7 合成正样本（判据必须命中）────────────────────────────────────────────
const PROBES = [
  [RE_LEG_MANUAL, '  manual = nebflow.shared.Defaults.RestartRecoveryManual', 'boot-leg manual wiring'],
  [RE_EXPLICIT_MANUAL, '  manual = false', 'hard-coded manual literal'],
  [RE_DEFAULT_MANUAL, '  def RestartRecoveryManual: Boolean =\n    sys.props.getOrElse("nebflow.startup.recovery.manual", "true").toBoolean', 'manual default literal'],
  [RE_DEFAULT_REVIVAL_KILL, '  def DelegateRevivalEnabled: Boolean =\n    sys.props.getOrElse("nebflow.delegate.revival.enabled", "true").toBoolean', 'revival kill-switch default'],
  [RE_MANUAL_FORK, '          case Right(candidates) if candidates.nonEmpty && manual =>', 'manual-first fork'],
  [RE_SWEEP_MANUAL_HELPER, '  private def sweepManualUnprotected(', 'manual sweep helper'],
  [RE_PENDING_STATUS, '              store.transitionObserved(task, PendingRecoveryStatus,', 'pending-recovery write'],
  [RE_AUTO_REVIVE_CALL, '                      DelegateRevival.reviveAll(', 'auto-revive call (kept, bypassed)'],
  [RE_ZERO_NOTIFY_RECEIPT, '      delivery: Either[String, String] = Right("suppressed:zero-notify-by-author-order")', 'zero-notify receipt'],
  [RE_ZERO_NOTIFY_COPY, '"; parent NOT notified (zero-notify by author order 2026-11-06', 'zero-notify copy'],
  [RE_RESTORE_ONE, '  def restoreOne(deps: DelegateRevival.Deps, store: SubAgentTaskStore, taskId: String)', 'manual restoreOne'],
  [RE_RESTORE_ALL, '  def restoreAll(', 'manual restoreAll'],
  [RE_HOST_INSTALL, '                                      nebflow.gateway.ManualRevivalHost.install(', 'boot installs the manual host'],
  [RE_ROUTE_ONE, '      case req @ POST -> Root / "delegates" / "restore" =>', 'restore REST arm'],
  [RE_ROUTE_ALL, '      case req @ POST -> Root / "delegates" / "restore-all" =>', 'restore-all REST arm'],
];

// ── T7b 负控样本：**改回开启**的形态必须被判据拒绝 ──────────────────────────
const AUTO_ON_SAMPLE = [
  '          manual = false',
  '                                        .recoverAll(manual = false)',
  '                                          manual = false',
  '                                              .recoverProject(rt, manual = false)',
].join('\n');

const failures = [];
const ok = [];

function read(rel) {
  const abs = join(ROOT, rel);
  if (!existsSync(abs)) return null;
  return readFileSync(abs, 'utf8');
}
const fresh = (re, flags) => new RegExp(re.source, flags);
const occurrences = (text, re) => {
  const m = text.match(fresh(re, 'g'));
  return m ? m.length : 0;
};
/** 去掉 `//` 行注释行（判据只看代码面；`manual = true` 出现在注释里不算字面量）。 */
const codeLines = (text) =>
  text
    .split('\n')
    .filter((line) => !line.trim().startsWith('//'))
    .join('\n');

// ── T1 scan face ────────────────────────────────────────────────────────────
const src = {};
const missing = [];
for (const [key, rel] of Object.entries(FILES)) {
  const text = read(rel);
  if (text === null) missing.push(rel);
  else src[key] = text;
}
if (missing.length > 0) {
  if (JSON_OUT) console.log(JSON.stringify({ failures: missing.map((f) => `T1 missing file: ${f}`) }, null, 2));
  else {
    console.error('[boot-revival-manual] T1 scan face incomplete — environment error:');
    for (const f of missing) console.error(`  ✗ ${f}`);
  }
  process.exit(2);
}
ok.push(`T1 scan face present (${Object.keys(FILES).length} files)`);

const expect = (label, condition, message) => {
  if (condition) ok.push(`${label}: ${message}`);
  else failures.push(`${label}: ${message}`);
};

// ── T2 boot wiring is manual (four legs read the switch) ────────────────────
const wired = occurrences(src.gateway, RE_LEG_MANUAL);
expect('T2', wired === 4, `GatewayMain boot legs read Defaults.RestartRecoveryManual (got ${wired}, want 4)`);
const literalHits = codeLines(src.gateway)
  .split('\n')
  .map((line, i) => [line, i])
  .filter(([line]) => RE_EXPLICIT_MANUAL.test(line));
expect(
  'T2',
  literalHits.length === 0,
  `GatewayMain carries no hard-coded manual literal in code lines (found ${literalHits.length})`
);

// ── T3 Defaults: both switches default to the safe/manual side ──────────────
expect('T3', RE_DEFAULT_MANUAL.test(src.defaults), 'RestartRecoveryManual defaults to "true" (manual) via nebflow.startup.recovery.manual');
expect('T3', RE_DEFAULT_REVIVAL_KILL.test(src.defaults), 'DelegateRevivalEnabled kill-switch default is "true" (no silent disable)');

// ── T4 manual-first fork in the sweep (auto leg kept but bypassed) ──────────
expect('T4', RE_MANUAL_FORK.test(src.sweep), 'manual-first fork (candidates.nonEmpty && manual) present');
expect('T4', RE_SWEEP_MANUAL_HELPER.test(src.sweep), 'manual sweep helper (sweepManualUnprotected) present');
expect('T4', RE_PENDING_STATUS.test(src.sweep), 'manual leg writes PendingRecoveryStatus (state saved, not judged dead)');
expect('T4', RE_AUTO_REVIVE_CALL.test(src.sweep), 'auto-revive call retained in code (bypassed by the switch, not deleted)');

// ── T5 zero notify on the salvage leg ──────────────────────────────────────
expect('T5', RE_ZERO_NOTIFY_RECEIPT.test(src.sweep), 'zero-notify receipt literal present');
expect('T5', RE_ZERO_NOTIFY_COPY.test(src.sweep), 'zero-notify task-row copy present');

// ── T6 manual leg usable (both entry points + boot install) ────────────────
expect('T6', RE_RESTORE_ONE.test(src.manualRevival), 'ManualRevival.restoreOne present');
expect('T6', RE_RESTORE_ALL.test(src.manualRevival), 'ManualRevival.restoreAll present');
expect('T6', RE_HOST_INSTALL.test(src.gateway), 'boot installs ManualRevivalHost (REST arms can read deps)');
expect('T6', RE_ROUTE_ONE.test(src.routes), 'POST /delegates/restore REST arm present');
expect('T6', RE_ROUTE_ALL.test(src.routes), 'POST /delegates/restore-all REST arm present');

// ── T7 probe-before-trust + T7b negative control ───────────────────────────
for (const [re, sample, label] of PROBES) {
  expect('T7', fresh(re).test(sample), `${label} detector hits its synthetic sample`);
}
// 负控：改回开启的形态必须被判据拒绝。
const autoWired = occurrences(AUTO_ON_SAMPLE, RE_LEG_MANUAL);
expect('T7b', autoWired === 0, 'auto-on sample matches ZERO manual-wiring sites (the gate WOULD go red on a flip)');
const autoLiteral = codeLines(AUTO_ON_SAMPLE)
  .split('\n')
  .filter((line) => RE_EXPLICIT_MANUAL.test(line)).length;
expect('T7b', autoLiteral === 4, `auto-on sample exposes 4 hard-coded literals (the T2 literal rule WOULD go red; got ${autoLiteral})`);

// ── T8 spec face (behavioural contracts declared) ──────────────────────────
for (const [label, re] of [
  ['MR1 manual boot sweep saves state / zero revive / zero notify', /MR1: manual boot sweep saves state/],
  ['MR3 restoreAll revives every eligible pending row', /MR3: restoreAll/],
  ['MR7 Defaults.RestartRecoveryManual defaults to manual', /MR7: Defaults\.RestartRecoveryManual defaults to manual/],
]) expect('T8', re.test(src.manualSpec), `${label} scenario present in ManualRevivalSpec`);
expect('T8', /V1: eligible orphan revives from transcript checkpoint/.test(src.revivalSpec), 'V1 auto-revival contract still declared in DelegateRevivalSpec');

// ── summary ────────────────────────────────────────────────────────────────
if (JSON_OUT) {
  console.log(JSON.stringify({ root: ROOT, checks: ok.length, failures, ok }, null, 2));
} else if (failures.length === 0) {
  console.log(`[boot-revival-manual] ${ok.length} checks — boot revival stays manual-only (auto leg off by default)  [OK]`);
  for (const line of ok) console.log(`  ✓ ${line}`);
} else {
  console.error(`[boot-revival-manual] ${failures.length} contract violation(s):`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  console.error(
    '\n修复口径：boot 复活腿只留手动腿 —— 四个 boot 调用点必须读 Defaults.RestartRecoveryManual（缺省手动），' +
      '不得写死 manual 字面量；收殓腿零通知；手动腿（ManualRevival + REST 臂）必须在场。'
  );
}
process.exit(failures.length === 0 ? 0 : 1);
