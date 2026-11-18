#!/usr/bin/env node
// check-node-stuck-notify.mjs — node-stuck 通报的**通报 + 事件记账**门禁
// （node-stuck-notify 批，2026-09-11）。
//
// Why a gate：本批交付的契约是**两条腿同时落地**——(a) 卡死/超窗挂等的节点向
// dispatcher（缺席则 root）注入一条可执行通报，(b) 同一动作里有**恰好一行**
// flow-map 审计事件（`node-stuck-notified`），且该行的 `delivered=` 如实回填。
// 缺任何一条腿都是静默失效形态：要么「通报说发了、其实没发」（无事件可对拍），
// 要么「事件说报了、其实没人收到」。再加上两条**保守性**契约——productive long
// task 不得误报（类② 假阳性不得产出 cause）、阈值必须可配置——共四条判据。
//
// ── 判据（任一不成立即红）────────────────────────────────────────────────
// ① **通报腿**：`NodeStuckNotify.notice` 内有 `DelegateNotify.inject(` 调用；
// ② **事件腿 + 记账**：同文件内 `eventAppend(` 恰有 1 处调用，且该调用传的 summary
//    由 `renderEventSummary(…, delivered)` 产出（= 通报与事件一一对上，且 delivered
//    如实回填）；
// ③ **保守闸门**：`causeOf` 只对类① true-stuck / 类③ env-lost 产出 cause（文本面
//    必须有这两个身份，且不得出现 `ClassFalsePositive` 被映射成 cause 的形态）；
// ④ **dispatcher → root 兜底**：`resolveTarget` 内含 `viaRoot = true` 的兜底路径；
// ⑤ **阈值可配置**：`Defaults.NodeStuckNotifyWaitingMs` 存在（system prop 现读），
//    `sweep` 有 `waitingThresholdMs` 参数；
// ⑥ **接线**：`TaskStuckWatcher.scan` 调用 `NodeStuckNotify.sweep(`（否则判据存在
//    但生产路径永不走）；
// ⑦ **场景在场**：回归 spec 内 S1–S6 六个场景标题逐字在场（缺场景 = 静默删测）；
// ⑧ **通报文本四段**：`renderNotice` 内 state / duration / activity / options 四段
//    齐备（产品契约）；
// ⑨ **Duplicate 不再无痕**（修复批 2026-11-18）：`AgentIdle` 每个
//    `LandOutcome.Duplicate` 分支都必须调用 `InputLanding.duplicateReason(`（落可读事件），
//    且 `InputLanding` 的诊断须显式命名 `taken-input/` 残件这一静默丢件形态。
//
// ── 用法 ────────────────────────────────────────────────────────────────
//   node scripts/check-node-stuck-notify.mjs           # 0 绿 / 1 红 / 2 环境错误
//   node scripts/check-node-stuck-notify.mjs --json    # 机器读

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const JSON_OUT = process.argv.slice(2).includes('--json');

const NOTIFY = join(ROOT, 'src/main/scala/nebflow/core/processor/NodeStuckNotify.scala');
const WATCHER = join(ROOT, 'src/main/scala/nebflow/core/processor/TaskStuckWatcher.scala');
const DEFAULTS = join(ROOT, 'src/main/scala/nebflow/shared/Defaults.scala');
const EVENTLOG = join(ROOT, 'src/main/scala/nebflow/core/project/FlowMapEventLog.scala');
const SPEC = join(ROOT, 'src/test/scala/nebflow/core/processor/NodeStuckNotifySpec.scala');
const AGENTIDLE = join(ROOT, 'src/main/scala/nebflow/agent/AgentIdle.scala');
const INPUTLANDING = join(ROOT, 'src/main/scala/nebflow/agent/InputLanding.scala');

function read(path) {
  try {
    return readFileSync(path, 'utf8');
  } catch {
    console.error(`[node-stuck-notify] cannot read ${path} — environment error`);
    process.exit(2);
  }
}

const count = (text, needle) => text.split(needle).length - 1;

const notify = read(NOTIFY);
const watcher = read(WATCHER);
const defaults = read(DEFAULTS);
const eventlog = read(EVENTLOG);
const spec = read(SPEC);
const agentIdle = read(AGENTIDLE);
const inputLanding = read(INPUTLANDING);

const failures = [];
const ok = [];

// ① 通报腿
if (count(notify, 'DelegateNotify.inject(') === 1) ok.push('injection call site present (1×)');
else failures.push(`expected exactly 1 DelegateNotify.inject( call, found ${count(notify, 'DelegateNotify.inject(')}`);

// ② 事件腿 + 记账（恰好一行，且 summary 由 delivered 回填）
const callAppend = count(notify, 'eventAppend(nodeRef.workspace');
if (callAppend === 1) ok.push('flow-map event append call site present (1×) — one line per notification');
else failures.push(`expected exactly 1 eventAppend( call site, found ${callAppend}`);
if (notify.includes('renderEventSummary(rec, cause, ageMs, nodeRef, target, delivered)'))
  ok.push('event summary derives from the delivered flag (notification ⇄ event accounting)');
else
  failures.push(
    'event summary must be produced by renderEventSummary(..., delivered) — same delivered flag as the injection result'
  );

if (count(eventlog, 'val NodeStuckNotifiedType: String = "node-stuck-notified"') === 1)
  ok.push('audit event type registered in FlowMapEventLog');
else failures.push('FlowMapEventLog must register NodeStuckNotifiedType = "node-stuck-notified"');
if (notify.includes('FlowMapEventLog.NodeStuckNotifiedType'))
  ok.push('NodeStuckNotify writes the registered event type');
else failures.push('NodeStuckNotify must use FlowMapEventLog.NodeStuckNotifiedType');

// ③ 保守闸门
if (
  notify.includes('TaskStuckWatcher.ClassTrueStuck || cls.cls == TaskStuckWatcher.ClassEnvLost') &&
  !/ClassFalsePositive[\s\S]{0,200}Then Some\(Cause/.test(notify)
) {
  ok.push('cause is produced only for class ① true-stuck / class ③ env-lost (productive false positives suppressed)');
} else {
  failures.push('causeOf must gate on ClassTrueStuck / ClassEnvLost only (never on ClassFalsePositive)');
}

// ④ dispatcher → root 兜底
if (notify.includes('viaRoot = true')) ok.push('root fallback path present (viaRoot = true)');
else failures.push('resolveTarget must keep the root-fallback path (viaRoot = true)');

// ⑤ 阈值可配置
if (/def NodeStuckNotifyWaitingMs: Long =\s*\n?\s*sys\.props\.getOrElse\("nebflow\.nodeStuckNotify\.waitingMs"/.test(defaults))
  ok.push('waiting threshold is a live system property (nebflow.nodeStuckNotify.waitingMs)');
else failures.push('Defaults.NodeStuckNotifyWaitingMs must read system prop nebflow.nodeStuckNotify.waitingMs');
if (notify.includes('waitingThresholdMs: Long')) ok.push('sweep exposes configurable waitingThresholdMs');
else failures.push('sweep must expose a configurable waitingThresholdMs parameter');

// ⑥ 接线（判据取**调用点**，不是裸标识符：参数声明/类型引用也会含 `NodeStuckNotify`，
//    按标识符判会在「判据都在、接线被删」时给假绿）
if (/NodeStuckNotify\s*\n\s*\.sweep\(/.test(watcher))
  ok.push('TaskStuckWatcher.scan wires NodeStuckNotify.sweep (call site present)');
else failures.push('TaskStuckWatcher.scan must call NodeStuckNotify.sweep (unwired notification = silent no-op)');

// ⑦ 四场景在场
const SCENARIOS = [
  ['S1', 'over threshold → dispatcher injection + flow-map event'],
  ['S2', 'productive long task'],
  ['S3', 'dispatcher absent'],
  ['S4', 'configurable threshold works'],
  ['S5', 'no-progress / tool-timeout cause split'],
  ['S6', 'healthy session'],
];
for (const [label, needle] of SCENARIOS) {
  const re = new RegExp(`test\\("${label}: [^"]*${needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[^"]*"`);
  if (re.test(spec)) ok.push(`scenario ${label} present`);
  else failures.push(`scenario ${label} (${needle}) missing from the regression spec`);
}

// ⑧ 通报文本四段
const segments = ['state: ', 'duration: ', 'activity: ', 'options: '];
const missing = segments.filter((s) => !notify.includes(s));
if (missing.length === 0) ok.push('notice text carries state / duration / activity / options');
else failures.push(`notice text is missing segment(s): ${missing.join(', ')}`);

// ⑨ Duplicate 不再无痕（修复批 2026-11-18）：每个 `LandOutcome.Duplicate` 分支都必须在
//    300 字符内调用 `duplicateReason(`（落一条可读事件）；且 InputLanding 提供该诊断的
//    纯函数并把「taken-input/ 残件」这一静默丢件形态显式命名。
const dupBranches = agentIdle.match(/LandOutcome\.Duplicate\s*=>/g) || [];
const dupReasoned = agentIdle.match(/LandOutcome\.Duplicate\s*=>[\s\S]{0,360}?duplicateReason\(/g) || [];
if (dupBranches.length > 0 && dupReasoned.length === dupBranches.length)
  ok.push(`every LandOutcome.Duplicate branch (${dupBranches.length}) emits a readable reason (no silent drop)`);
else
  failures.push(`silent Duplicate: ${dupBranches.length} branch(es) but only ${dupReasoned.length} call duplicateReason(`);
if (inputLanding.includes('def duplicateReason(') && inputLanding.includes('taken-input/ residue'))
  ok.push('InputLanding.duplicateReason names the taken-input/ residue (silent-drop diagnosis)');
else failures.push('InputLanding.duplicateReason must exist and name the taken-input/ residue');

if (JSON_OUT) {
  console.log(JSON.stringify({ checks: ok.length, failures, ok }, null, 2));
} else if (failures.length === 0) {
  console.log(`[node-stuck-notify] ${ok.length} checks — notification + event accounting intact  [OK]`);
  for (const line of ok) console.log(`  ✓ ${line}`);
} else {
  console.error(`[node-stuck-notify] ${failures.length} contract violation(s):`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  console.error(
    '\n修复口径：node-stuck 通报必须「注入一条可执行通报 + 恰好一行 flow-map 审计事件（delivered 如实回填）」' +
      '，且只对类① 真卡死 / 类③ 环境失效 / 超窗挂等产出；阈值可配置；dispatcher 缺席走 root 兜底。'
  );
}

process.exit(failures.length === 0 ? 0 : 1);
