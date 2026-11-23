#!/usr/bin/env node
// check-delegate-mode-slot.mjs — delegate 归槽闸（归槽批 2026-11-18）。
//
// 作者令（逐字，2026-11-18 14:28）：「delegate会话这个划分的太粗了啊 我们是分了
// kernel node dispatcher和subagent的 怎么能都分给subagent呢 这明显是粒度没对齐」。
//
// 现象（生产实证 `<dataRoot>/delegates/` 1013 条记录，2026-11-18 现读）：mode=kernel
// 808 条 + mode=dispatcher 96 条，**全部**带 `delegate-nebflow-<8hex>` 这一条 id
// 形状（生成点 DelegateTool.scala:1138 与 mode 无关），而 `delegate-kernel-` 形状
// 盘上零条 ⇒ 按前缀归槽让这 904 条身份全部吃 subagent 槽链（槽链压过钉链 =
// 「配置 cmdcode 实际跑 terra」的成因之一）。
//
// ── Judgement table (the whole gate) ────────────────────────────────────────
//   T1  Scan face = 4 files must exist: ModelRoles.scala (归槽判据单点),
//        DelegateRegistry.scala (真相来源 raw mode 读面),
//        AgentSessionExecution.scala (请求面单点), ModelRolesSpec.scala (判据钉).
//   T2  Mode is the truth source = ModelRoles declares the mode→slot table
//        `DelegateModeWires` carrying all four authored words
//        (kernel|dispatcher|node|subagent), and the `delegate-` branch resolves
//        THROUGH it (`delegateWireWordOf(delegateMode)`), with the mode input on
//        the public signature.
//   T3  CORE: no prefix-only subagent = the forbidden form
//        `startsWith("delegate-") … then SubagentWire` has ZERO occurrences.
//        「delegate 会话不得仅凭前缀归 subagent 槽」。
//   T4  Unknown mode is not swallowed = a **raw** (unfiltered) mode read exists
//        (`DelegateRegistry.rawModeOf`, no `isModeWord` filter inside its body),
//        an explicit readable reason exists (`delegateSlotUnresolvedReason`) and
//        it is actually recorded (`recordUnresolvedDelegateSlot`).
//   T5  Request face wired = the record mode still reaches slotChainOf with the
//        composition order slot > pin > base, and it happens at ONE point. 该
//        单点自 P1 统一模型层（feat/model-decision-unify，2026-11-22 落地）起是
//        `ModelDecision.composeAgentModel`：AgentSessionExecution 只传
//        (sessionId, rawModeOf, base, pin) 并取 `._1`，内联组合段已消失。
//        闸按「不变式」判而不是按「旧位置」判：仍会咬住回退（谁把组合段搬回
//        请求面内联即红）与参数错位（slotChainOf 拿不到 record mode 即红）。
//   T6  Spec pins the contract = the four mode cases, the unknown-mode fallback
//        case, the retired prefix rule and the priority regression are asserted.
//   T7  Probe-before-trust = every detector regex hits a synthetic sample
//        (zero-hit readings are only trusted after a positive probe — same
//        discipline as check-tag-format.mjs T5 / check-dispatcher-seal.mjs).
//
// Run: node scripts/check-delegate-mode-slot.mjs
// Exit 0 = all green; 1 = drift detected (file:line readings printed).

import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

const FILES = {
  roles: 'src/main/scala/nebflow/core/ModelRoles.scala',
  registry: 'src/main/scala/nebflow/core/delegate/DelegateRegistry.scala',
  exec: 'src/main/scala/nebflow/agent/AgentSessionExecution.scala',
  // P1 统一模型层（feat/model-decision-unify，2026-11-22 落地）：请求面组合
  // （槽 > 钉链 > base）从 AgentSessionExecution 内联段下移到这个单点。
  decision: 'src/main/scala/nebflow/llm/ModelDecision.scala',
  spec: 'src/test/scala/nebflow/core/ModelRolesSpec.scala',
};

// ── T2: the mode → slot table is the truth source ───────────────────────────
const RE_TABLE = /lazy val DelegateModeWires: List\[\(String, String\)\] = List\(/;
const RE_MAP_KERNEL = /\(nebflow\.core\.delegate\.DualModeConfig\.ModeKernel, KernelWire\)/;
const RE_MAP_DISPATCHER = /\(nebflow\.core\.delegate\.DualModeConfig\.ModeDispatcher, DispatcherWire\)/;
const RE_MAP_NODE = /\(NodeWire, NodeWire\)/;
const RE_MAP_SUBAGENT = /\(SubagentWire, SubagentWire\)/;
const RE_SIG = /def slotOfSessionId\(\s*sessionId: Option\[String\],\s*delegateMode: Option\[String\] = None\s*\): Option\[String\]/;
const RE_BRANCH_THROUGH_MODE = /startsWith\("delegate-"\)\s*then\s+delegateWireWordOf\(delegateMode\)/;

// ── T3 (CORE): the forbidden prefix-only classification ─────────────────────
const RE_PREFIX_ONLY_SUBAGENT = /startsWith\("delegate-"\)\s*then\s+SubagentWire\b/;

// ── T4: the fallback never swallows the truth ──────────────────────────────
const RE_RAW_MODE = /def rawModeOf\(sessionId: Option\[String\]\): Option\[String\]/;
const RE_REASON =
  /def delegateSlotUnresolvedReason\(sessionId: Option\[String\], mode: Option\[String\]\): Option\[String\]/;
const RE_RECORDED = /recordUnresolvedDelegateSlot\(sessionId, delegateMode\)/;

// ── T5: the request face is wired and the order is untouched ───────────────
// 请求面（AgentSessionExecution）必须把 record mode 交给统一层单点，参数顺序
// (sessionId, rawModeOf(sessionId), base, pin) 逐位钉住。
const RE_ROUTE =
  /ModelDecision\s*\.composeAgentModel\(\s*stateForLlm\.sessionId,\s*nebflow\.core\.delegate\.DelegateRegistry\.rawModeOf\(stateForLlm\.sessionId\),\s*freshDef\.model,\s*effectivePin\s*\)/;
// 组合序只在统一层出现一次：先 slotChainOf(record mode)，否则 pin 物化，否则 base。
const RE_ORDER_AT_POINT =
  /slotChainOf\(\s*sessionId,\s*delegateMode\s*\)\s*match[\s\S]*?pin match[\s\S]*?Some\(p\.toAgentModelConfig\)/;
// 回退护栏：请求面不得再出现旧内联组合（`slotChainOf(...).orElse(agentModelWithPin(...))`）。
const RE_OLD_INLINE = /slotChainOf\([\s\S]*?\)\s*\.orElse\(agentModelWithPin\(freshDef\.model, effectivePin\)\)/;

// ── T6: the spec pins the contract ─────────────────────────────────────────
// 换行不敏感（全仓格式化批 2026-11-21）：scalafmt 把 `slotOfDelegateMode(Some("kernel")),
// Some("kernel")` 折成两行，判据必须允许换行 —— 否则红的是排版而不是规范行为。
const SPEC_MARKERS = [
  ['four-word mode table', /DelegateModeWires\.map\(_\._1\)/],
  ['mode=kernel case', /slotOfDelegateMode\(Some\("kernel"\)\),\s*Some\("kernel"\)/],
  ['mode=node case', /slotOfDelegateMode\(Some\("node"\)\),\s*Some\("node"\)/],
  ['mode=dispatcher case', /slotOfDelegateMode\(Some\("dispatcher"\)\),\s*Some\("project-dispatcher"\)/],
  ['mode=subagent case', /slotOfDelegateMode\(Some\("subagent"\)\),\s*Some\("subagent"\)/],
  ['unknown-mode fallback case', /delegateSlotUnresolvedReason\(sid, Some\("merger"\)\)/],
  ['retired prefix rule asserted', /delegate- 前缀本身不再声明任何类/],
  ['never-stuff-into-subagent assertion', /兜底绝不静默塞进 subagent 槽/],
  ['priority regression asserted', /槽链 > 派发钉链 > agent 自身链/],
];

// ── T7: synthetic samples — the probes that make zero-hit readings trustworthy
const PROBES = [
  [RE_TABLE, '  lazy val DelegateModeWires: List[(String, String)] = List(', 'mode→slot table detector'],
  [RE_MAP_KERNEL, '    (nebflow.core.delegate.DualModeConfig.ModeKernel, KernelWire),', 'kernel map row detector'],
  [RE_MAP_NODE, '    (NodeWire, NodeWire),', 'node map row detector'],
  [RE_MAP_SUBAGENT, '    (SubagentWire, SubagentWire),', 'subagent map row detector'],
  [
    RE_SIG,
    '  def slotOfSessionId(\n    sessionId: Option[String],\n    delegateMode: Option[String] = None\n  ): Option[String] =',
    'mode-aware signature detector',
  ],
  [
    RE_BRANCH_THROUGH_MODE,
    '        else if lower.startsWith("delegate-") then delegateWireWordOf(delegateMode).getOrElse("")',
    'delegate-branch-through-mode detector',
  ],
  [
    RE_PREFIX_ONLY_SUBAGENT,
    '        else if lower.startsWith("delegate-") then\n          SubagentWire',
    'prefix-only-subagent detector',
  ],
  [
    RE_RAW_MODE,
    '  def rawModeOf(sessionId: Option[String]): Option[String] =',
    'raw-mode-read detector',
  ],
  [
    RE_REASON,
    '  def delegateSlotUnresolvedReason(sessionId: Option[String], mode: Option[String]): Option[String] =',
    'readable-reason detector',
  ],
  [
    RE_RECORDED,
    '    recordUnresolvedDelegateSlot(sessionId, delegateMode)',
    'reason-recorded detector',
  ],
  [
    RE_ROUTE,
    '            agentModel = nebflow.llm.ModelDecision\n              .composeAgentModel(\n                stateForLlm.sessionId,\n                nebflow.core.delegate.DelegateRegistry.rawModeOf(stateForLlm.sessionId),\n                freshDef.model,\n                effectivePin\n              )\n              ._1,',
    'request-face-routing detector',
  ],
  [
    RE_ORDER_AT_POINT,
    '    ModelRoles.slotChainOf(sessionId, delegateMode) match\n      case Some(slotChain) =>\n        (Some(slotChain), Nil)\n      case None =>\n        pin match\n          case Some(p) => (Some(p.toAgentModelConfig), Nil)',
    'composition-order-at-single-point detector',
  ],
  [
    RE_OLD_INLINE,
    '            agentModel = nebflow.core.ModelRoles\n              .slotChainOf(a, b)\n              .orElse(agentModelWithPin(freshDef.model, effectivePin)),',
    'inline-composition-retired detector',
  ],
];

const failures = [];
let checks = 0;

function pass(msg) { checks++; console.log(`OK:   ${msg}`); }
function fail(msg) { checks++; failures.push(msg); console.log(`FAIL: ${msg}`); }
function fresh(re) { return new RegExp(re.source, re.flags); }
function occurrences(text, re) {
  const m = text.match(new RegExp(re.source, re.flags.includes('g') ? re.flags : re.flags + 'g'));
  return m ? m.length : 0;
}
function show(rel, line, text) { return `${rel}:${line}: ${text.trim()}`; }
function firstHit(text, re) {
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) if (fresh(re).test(lines[i])) return { line: i + 1, text: lines[i] };
  return null;
}
/** Slice a member body: from the first line matching `startRe` to the next blank line at member indentation. */
function memberBody(text, startRe) {
  const hit = firstHit(text, startRe);
  if (!hit) return null;
  const lines = text.split('\n');
  const out = [];
  for (let i = hit.line - 1; i < lines.length; i++) {
    const l = lines[i];
    if (i > hit.line - 1 && /^\s*$/.test(l)) break;
    out.push(l);
  }
  return { text: out.join('\n'), line: hit.line };
}

// ── T1: scan face ───────────────────────────────────────────────────────────
const files = {};
for (const [key, rel] of Object.entries(FILES)) {
  const abs = join(ROOT, rel);
  if (!existsSync(abs)) { fail(`T1 scan face missing: ${rel}`); continue; }
  files[key] = readFileSync(abs, 'utf8');
}
if (Object.keys(files).length !== Object.keys(FILES).length) {
  console.log(`\nFAILED (${failures.length} problem(s)) — scan face incomplete`);
  process.exit(1);
}
pass(`T1 scan face present: ${Object.values(FILES).join(', ')}`);

// ── T7: probe-before-trust ─────────────────────────────────────────────────
for (const [re, sample, label] of PROBES) {
  if (fresh(re).test(sample)) pass(`T7 ${label} hits its synthetic sample`);
  else fail(`T7 ${label} MISSES its synthetic sample (detector is dead): ${sample.trim().split('\n')[0]}`);
}

// ── T2: mode is the truth source ───────────────────────────────────────────
{
  const roles = files.roles;
  const t = firstHit(roles, RE_TABLE);
  if (t) pass(`T2 mode→slot table declared: ${show(FILES.roles, t.line, t.text)}`);
  else fail(`T2 the mode→slot table (DelegateModeWires) is gone from ${FILES.roles}`);

  for (const [re, label] of [
    [RE_MAP_KERNEL, 'kernel'],
    [RE_MAP_DISPATCHER, 'dispatcher'],
    [RE_MAP_NODE, 'node'],
    [RE_MAP_SUBAGENT, 'subagent'],
  ]) {
    const h = firstHit(roles, re);
    if (h) pass(`T2 table row '${label}' present: ${show(FILES.roles, h.line, h.text)}`);
    else fail(`T2 table row '${label}' is missing — the four authored words must all be mapped: ${FILES.roles}`);
  }

  const s = memberBody(roles, /def slotOfSessionId\(/);
  if (s && RE_SIG.test(roles)) pass(`T2 slotOfSessionId carries the mode input: ${show(FILES.roles, s.line, s.text.split('\n')[0])}`);
  else fail(`T2 slotOfSessionId lost its mode input — a delegate- id would again be classified by prefix alone: ${FILES.roles}`);

  const b = firstHit(roles, RE_BRANCH_THROUGH_MODE);
  if (b) pass(`T2 delegate- branch resolves THROUGH the table: ${show(FILES.roles, b.line, b.text)}`);
  else fail(`T2 the delegate- branch no longer resolves through delegateWireWordOf(delegateMode): ${FILES.roles}`);
}

// ── T3 (CORE): no prefix-only subagent ─────────────────────────────────────
{
  const n = occurrences(files.roles, RE_PREFIX_ONLY_SUBAGENT);
  if (n === 0) {
    pass('T3 CORE delegate sessions are NEVER classified into the subagent slot by prefix alone (0 hits)');
  } else {
    const h = firstHit(files.roles, RE_PREFIX_ONLY_SUBAGENT);
    fail(`T3 CORE prefix-only delegate⇒subagent classification is back (${n}x): ${show(FILES.roles, h.line, h.text)}`);
  }
}

// ── T4: the fallback never swallows the truth ──────────────────────────────
{
  const raw = memberBody(files.registry, /def rawModeOf\(/);
  if (raw) {
    if (/isModeWord/.test(raw.text)) {
      fail(`T4 rawModeOf filters by isModeWord — "mode present but unknown" folds into "no mode": ${show(FILES.registry, raw.line, raw.text.split('\n')[0])}`);
    } else {
      pass(`T4 DelegateRegistry.rawModeOf is the UNFILTERED mode read (no isModeWord in its body): ${show(FILES.registry, raw.line, raw.text.split('\n')[0])}`);
    }
  } else {
    fail(`T4 DelegateRegistry.rawModeOf is gone — the slot judgement has no authoritative truth source: ${FILES.registry}`);
  }

  const r = firstHit(files.roles, RE_REASON);
  if (r) pass(`T4 readable unresolved reason declared: ${show(FILES.roles, r.line, r.text)}`);
  else fail(`T4 delegateSlotUnresolvedReason is gone — an unresolved delegate mode would be swallowed silently: ${FILES.roles}`);

  const rec = firstHit(files.roles, RE_RECORDED);
  if (rec) pass(`T4 unresolved reason is recorded at the request-face single point: ${show(FILES.roles, rec.line, rec.text)}`);
  else fail(`T4 the unresolved reason is never recorded (recordUnresolvedDelegateSlot call gone): ${FILES.roles}`);

  // The reason must name BOTH fallback shapes (absent vs unrecognized) — and must
  // not silently answer "subagent".
  const body = memberBody(files.roles, /def delegateSlotUnresolvedReason\(/);
  if (body) {
    const hasAbsent = /has no recorded mode/.test(body.text);
    const hasUnknown = /is outside the slot map/.test(body.text);
    if (hasAbsent && hasUnknown) pass('T4 both fallback shapes are stated separately (mode absent vs mode unrecognized)');
    else fail(`T4 the fallback reason must state absent (${hasAbsent}) and unrecognized (${hasUnknown}) separately`);
    if (/SubagentWire/.test(body.text)) fail('T4 the fallback reason hands out the subagent slot — 兜底静默塞进 subagent');
    else pass('T4 the fallback never hands out the subagent slot');
  }
}

// ── T5: the request face is wired, the order is untouched ──────────────────
{
  if (RE_ROUTE.test(files.exec)) {
    const line = firstHit(files.exec, /\.composeAgentModel\(/);
    pass(`T5 request face feeds the record mode into the single composition point: ${show(FILES.exec, line ? line.line : 0, 'ModelDecision.composeAgentModel(sessionId, DelegateRegistry.rawModeOf(sessionId), base, pin)')}`);
  } else fail(`T5 AgentSessionExecution no longer routes the record mode into ModelDecision.composeAgentModel: ${FILES.exec}`);

  if (RE_ORDER_AT_POINT.test(files.decision)) {
    const line = firstHit(files.decision, /slotChainOf\(\s*sessionId,\s*delegateMode\s*\)/);
    pass(`T5 composition order preserved at the single point: slot chain > dispatch pin > agent chain: ${show(FILES.decision, line ? line.line : 0, 'slotChainOf(sessionId, delegateMode) match … pin match … toAgentModelConfig')}`);
  } else {
    fail(`T5 the slot > pin > base composition is gone from the single point: ${FILES.decision}`);
  }

  if (RE_OLD_INLINE.test(files.exec)) {
    fail(`T5 the old inline composition came back at the request face (two truth sources): ${FILES.exec}`);
  } else {
    pass('T5 the request face keeps no inline slot > pin > base composition (single point only)');
  }
}

// ── T6: the spec pins the contract ─────────────────────────────────────────
for (const [label, re] of SPEC_MARKERS) {
  if (re.test(files.spec)) pass(`T6 spec pins: ${label}`);
  else fail(`T6 spec lost the assertion group: ${label} (${FILES.spec})`);
}

// ── summary ─────────────────────────────────────────────────────────────────
if (failures.length === 0) {
  console.log(`\nPASS: delegate sessions are classified by their recorded mode (kernel|node|dispatcher|subagent), never by the delegate- prefix alone (${checks} assertions).`);
  process.exit(0);
}
console.log(`\nFAILED (${failures.length} of ${checks} assertions):`);
for (const f of failures) console.log(`  - ${f}`);
process.exit(1);
