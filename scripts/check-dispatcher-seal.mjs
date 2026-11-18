#!/usr/bin/env node
// check-dispatcher-seal.mjs — dispatcher 模式封存闸（dispatcher-seal 批 2026-11-18）。
//
// 作者令（逐字，2026-11-18）：「先不修复 派个批 报delegate的dispatcher模式封存
// 然后立即重启 让kernel来做」。现象：dispatcher 会话反复挂死——起链正常
// （NodeList → ChainWrite(2 nodes)），节点到终态（NODE … REVIEW … COMPLETED）后
// 父会话再也不被唤醒（当日多次，一挂 2h+）。封存 = 派发显式拒 + 实现全保留 +
// 配置开关可恢复（stand.json `delegate.dispatcherMode` = "sealed"（缺省）/"enabled"）。
//
// ── Judgement table (the whole gate) ────────────────────────────────────────
//   T1  Scan face    = 3 files must exist:
//        - src/main/scala/nebflow/core/delegate/DispatcherSeal.scala (the seal)
//        - src/main/scala/nebflow/core/tools/DelegateTool.scala       (dispatch point)
//        - src/test/scala/nebflow/core/delegate/DispatcherSealSpec.scala (S1/S2/S3)
//   T2  Sealed-by-default = the fail-safe parse in DispatcherSeal.scala ends in
//        `.getOrElse(Sealed)` (absent node / absent key / unintelligible word all
//        land on Sealed — fail-closed), and the config node decoder carries the
//        raw `delegate` node (shared/config.scala). "dispatcher 处于封存态".
//   T3  Rejection path wired = DelegateTool's dispatch leg resolves through
//        `DispatcherSeal.resolveForDispatch` (the explicit-reject path — NOT a
//        bare ModeParam.resolve that would let dispatcher through), and the
//        sealed message carries its own error code DELEGATE_DISPATCHER_SEALED
//        plus the mode="kernel" way out and the recovery switch verbatim.
//        "拒绝路径存在".
//   T4  Recoverable switch = DispatcherSeal.scala declares `Enabled = "enabled"`
//        and the parse admits exactly that word (`filter(_ == Enabled)`), so
//        flipping stand.json restores pre-seal behaviour with zero code change.
//        "开关可恢复".
//   T5  Probe-before-trust = every detector regex must hit a synthetic sample
//        (zero-hit readings are only trusted after a positive probe — same
//        discipline as check-tag-format.mjs T5).
//
// Run: node scripts/check-dispatcher-seal.mjs
// Exit 0 = all green; 1 = drift detected (file:line readings printed).

import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

const FILES = {
  seal: 'src/main/scala/nebflow/core/delegate/DispatcherSeal.scala',
  tool: 'src/main/scala/nebflow/core/tools/DelegateTool.scala',
  spec: 'src/test/scala/nebflow/core/delegate/DispatcherSealSpec.scala',
  config: 'src/main/scala/nebflow/shared/config.scala',
};

// T2: fail-closed default in the config parse (getOrElse(Sealed), not Enabled).
const RE_SEALED_DEFAULT = /\.getOrElse\(Sealed\)/;
// T2b: the raw delegate node reaches the config decoder (hot-read face).
const RE_CONFIG_NODE = /delegate:\s*Option\[io\.circe\.Json\]\s*=\s*None/;
// T3: the dispatch leg resolves THROUGH the seal gate (not bare ModeParam.resolve).
const RE_GATE_WIRED = /DispatcherSeal\.resolveForDispatch\(/;
// T3b: the dispatch point no longer resolves modes bare (the seal cannot be
// bypassed by keeping an un-gated resolve at the dispatch site).
const RE_BARE_RESOLVE = /^\s*nebflow\.core\.delegate\.ModeParam\.resolve\(/;
// T3c: the sealed rejection carries a seal-specific code + way out + switch.
const RE_SEAL_CODE = /DELEGATE_DISPATCHER_SEALED/;
const RE_SEAL_WAYOUT = /mode=\\"kernel\\"/;
const RE_SEAL_SWITCH = /delegate\.dispatcherMode=\\"enabled\\"/;
// T4: exactly-one recovery word, admitted by the parse.
const RE_ENABLED_WORD = /val Enabled: String = "enabled"/;
const RE_ENABLED_ADMIT = /\.filter\(_ == Enabled\)/;

// T5: synthetic samples — the probes that make zero-hit readings trustworthy.
const PROBE_SEALED_DEFAULT = '      .getOrElse(Sealed)';
const PROBE_GATE_WIRED = '        DispatcherSeal.resolveForDispatch(modeParam, dispatcherSealMode) match';
const PROBE_BARE_RESOLVE = '        nebflow.core.delegate.ModeParam.resolve(modeParam) match';
const PROBE_SEAL_WAYOUT = '  val m = "dispatch with mode=\\"kernel\\" instead"';
const PROBE_SEAL_SWITCH = '  val s = "delegate.dispatcherMode=\\"enabled\\" in stand.json"';
const PROBE_ENABLED_ADMIT = '      .filter(_ == Enabled)';

const failures = [];
let checks = 0;

function pass(msg) { checks++; console.log(`OK:   ${msg}`); }
function fail(msg) { checks++; failures.push(msg); console.log(`FAIL: ${msg}`); }
function fresh(re, flags) { return new RegExp(re.source, flags); }
function occurrences(text, re) { const m = text.match(fresh(re, 'g')); return m ? m.length : 0; }
function show(rel, line, text) { return `${rel}:${line}: ${text.trim()}`; }
function firstHit(text, re) {
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) if (fresh(re).test(lines[i])) return { line: i + 1, text: lines[i] };
  return null;
}

// ── T1: scan face present ───────────────────────────────────────────────────
const files = {};
for (const [key, rel] of Object.entries(FILES)) {
  const abs = join(ROOT, rel);
  if (!existsSync(abs)) { fail(`T1 scan face missing: ${rel}`); continue; }
  files[key] = readFileSync(abs, 'utf8');
}
const present = Object.keys(files).length;
if (present !== Object.keys(FILES).length) {
  console.log(`\nFAILED (${failures.length} problem(s)) — scan face incomplete`);
  process.exit(1);
}
pass(`T1 scan face present: ${Object.values(FILES).join(', ')}`);

// ── T5: probe-before-trust ──────────────────────────────────────────────────
const probes = [
  [RE_SEALED_DEFAULT, PROBE_SEALED_DEFAULT, 'sealed-default detector'],
  [RE_GATE_WIRED, PROBE_GATE_WIRED, 'gate-wired detector'],
  [RE_BARE_RESOLVE, PROBE_BARE_RESOLVE, 'bare-resolve detector'],
  [RE_SEAL_WAYOUT, PROBE_SEAL_WAYOUT, 'way-out detector'],
  [RE_SEAL_SWITCH, PROBE_SEAL_SWITCH, 'switch-name detector'],
  [RE_ENABLED_ADMIT, PROBE_ENABLED_ADMIT, 'enabled-admit detector'],
];
for (const [re, sample, label] of probes) {
  if (fresh(re).test(sample)) pass(`T5 ${label} hits its synthetic sample`);
  else fail(`T5 ${label} MISSES its synthetic sample (detector is dead): ${sample.trim()}`);
}

// ── T2: sealed is the fail-safe default ─────────────────────────────────────
{
  const seal = files.seal;
  const n = occurrences(seal, RE_SEALED_DEFAULT);
  if (n >= 1) pass(`T2 fail-closed default present (${n}x .getOrElse(Sealed)): ${FILES.seal}`);
  else fail(`T2 sealed default parse is gone — unintelligible config would no longer land on Sealed: ${FILES.seal}`);

  const h = firstHit(seal, RE_ENABLED_WORD);
  if (h) pass(`T4 recovery word declared: ${show(FILES.seal, h.line, h.text)}`);
  else fail(`T4 recovery word "enabled" is not declared: ${FILES.seal}`);

  const a = occurrences(seal, RE_ENABLED_ADMIT);
  if (a === 1) pass(`T4 parse admits exactly the Enabled word (1x .filter(_ == Enabled))`);
  else fail(`T4 enabled-admission count != 1 (got ${a}) — the switch must admit precisely "enabled"`);

  if (RE_SEAL_CODE.test(seal)) pass(`T3 seal-specific error code present: DELEGATE_DISPATCHER_SEALED`);
  else fail(`T3 seal rejection no longer carries its own code (DELEGATE_DISPATCHER_SEALED)`);

  const w = firstHit(seal, RE_SEAL_WAYOUT);
  if (w) pass(`T3 sealed message points at the kernel way out: ${show(FILES.seal, w.line, w.text)}`);
  else fail(`T3 sealed message lost the mode=\"kernel\" way out`);

  const s = firstHit(seal, RE_SEAL_SWITCH);
  if (s) pass(`T3 sealed message names the recovery switch verbatim: ${show(FILES.seal, s.line, s.text)}`);
  else fail(`T3 sealed message lost the delegate.dispatcherMode=\"enabled\" recovery hint`);

  if (!RE_CONFIG_NODE.test(files.config)) fail(`T2 raw delegate node missing from the config decoder: ${FILES.config}`);
  else pass(`T2 raw delegate node reaches the config decoder (hot-read face): ${FILES.config}`);
}

// ── T3: the rejection path is wired at the dispatch point ───────────────────
{
  const tool = files.tool;
  const g = occurrences(tool, RE_GATE_WIRED);
  if (g === 1) pass(`T3 dispatch leg resolves through DispatcherSeal.resolveForDispatch (1x): ${FILES.tool}`);
  else fail(`T3 gate wiring count != 1 (got ${g}) — the dispatch leg must resolve through the seal gate: ${FILES.tool}`);

  const bare = firstHit(tool, RE_BARE_RESOLVE);
  if (!bare) pass(`T3 no un-gated bare ModeParam.resolve left at the dispatch site (seal cannot be bypassed)`);
  else fail(`T3 un-gated mode resolve at the dispatch site bypasses the seal: ${show(FILES.tool, bare.line, bare.text)}`);

  const hot = occurrences(tool, /DispatcherSeal\.modeFromConfigNode\(/);
  if (hot === 1) pass(`T3 seal switch is hot-read at dispatch time (1x modeFromConfigNode)`);
  else fail(`T3 seal switch hot-read count != 1 (got ${hot}) — the switch must be read from live config`);
}

// ── S-face: the three-group spec declares the seal contract ─────────────────
{
  const spec = files.spec;
  for (const marker of [
    ['S1 sealed-state explicit reject', /S1a 封存态下 dispatcher 被拒/],
    ['S2 enabled restores dispatcher', /S2a enabled 态下 dispatcher 原样放行/],
    ['S3 kernel regression untouched', /S3a mode=kernel 回归/],
    ['no silent downgrade assertion', /S1c 绝不静默降级/],
  ]) {
    if (marker[1].test(spec)) pass(`spec declares: ${marker[0]}`);
    else fail(`spec lost the assertion group: ${marker[0]}`);
  }
}

// ── summary ─────────────────────────────────────────────────────────────────
if (failures.length === 0) {
  console.log(`\nPASS: dispatcher mode is sealed-by-default with an explicit reject path and a recoverable switch (${checks} assertions).`);
  process.exit(0);
}
console.log(`\nFAILED (${failures.length} of ${checks} assertions):`);
for (const f of failures) console.log(`  - ${f}`);
process.exit(1);
