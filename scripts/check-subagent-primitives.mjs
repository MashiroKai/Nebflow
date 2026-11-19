#!/usr/bin/env node
// check-subagent-primitives.mjs — dispatcher 重设计 P0「抽件」的**外壳闸**。
//
// 职责切分（作者裁定 2026-11-19）：本闸**只**断言「抽取面 + 单源」——子会话
// 原语（完成桥 spy/startSpy、两段等待 awaitResult/startBudget、终态回收 reap、
// 结构化失败 token NeverStartedToken/Timeout）自 BlockingSubagent 抽成可复用
// 单点后，其**实现只在一处**、**无第二份复制**、**消费点经委托接线**；四条腿的
// **行为断言全部在 Scala 行为闸** `SubagentPrimitiveSpec.scala`，本静态闸不碰、
// 也不许硬做行为断言。
//
// ── 判据（任一不成立即红）────────────────────────────────────────────────
//  T1 scan face：三件在场——原语单点 / 委托消费点 / 行为闸。
//  T2 单源：被抽原语的六个成员定义（Timeout / NeverStartedToken / startBudget /
//     awaitResult / reap / startSpy / spy）都在单点里，且都**不在**消费点里
//     （搬家，不是复制）。
//  T3 无重复 token：结构化失败 token 字面量 "subagent-never-started" 在
//     src/main/scala 全域**恰一处**（单源的机器可查证据）。
//  T4 委托接线：消费点经 `export SubagentPrimitives.{…}` 暴露复用面，并经
//     调用点（startSpy / awaitResult / reap）委托给单点。
//  T5 行为闸四腿在场：行为闸声明四腿（腿① 帧匹配 / 腿② 预算耗尽 / 腿③ 超时 /
//     腿④ reap）且打在单点上（文本面在场，**非**行为断言）。
//  T6 probe-before-trust：每个检测器先在合成样本上命中（零命中读数才可信）。
//
// Usage: node scripts/check-subagent-primitives.mjs [--root <dir>]
// Exit:  0 绿 / 1 红（抽取面漂移）/ 2 环境缺失（SKIP：不是 Stand 检出）

import { existsSync, readFileSync, readdirSync, realpathSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_ROOT = fileURLToPath(new URL('..', import.meta.url));

// Path-identity guard: Node realpaths `import.meta.url` but keeps the spawned `argv[1]`
// verbatim (macOS /var → /private/var), so a raw string compare silently no-ops the CLI
// when the script lives under a symlinked root. Normalize both sides.
function invokedAsCli() {
  if (!process.argv[1]) return false;
  const real = path => {
    try { return realpathSync(path); } catch { return path; }
  };
  return real(process.argv[1]) === real(fileURLToPath(import.meta.url));
}

function parseArgs(args) {
  let root = SCRIPT_ROOT;
  for (let index = 0; index < args.length; index++) {
    if (args[index] === '--root') {
      const value = args[index + 1];
      if (!value) throw new Error('Missing value for --root');
      root = value;
      index++;
    } else throw new Error(`Unknown argument: ${args[index]}`);
  }
  return { root };
}

const FILES = {
  primitives: 'src/main/scala/nebflow/core/tools/SubagentPrimitives.scala',
  consumer: 'src/main/scala/nebflow/core/tools/BlockingSubagent.scala',
  spec: 'src/test/scala/nebflow/core/tools/SubagentPrimitiveSpec.scala',
};

// T2: 六个成员定义头（每个必须恰在单点里、且不在消费点里）。
const RE_DEFS = {
  'val Timeout': /val Timeout: FiniteDuration = 15\.minutes/,
  'val NeverStartedToken': /val NeverStartedToken: String = "subagent-never-started"/,
  'def startBudget': /def startBudget: FiniteDuration/,
  'def awaitResult': /def awaitResult\(/,
  'def reap': /def reap\(/,
  'def startSpy': /def startSpy\(/,
  'def spy': /def spy\(/,
};
// T3: 结构化失败 token 字面量（单源 → 全域恰一处）。
const RE_TOKEN_LITERAL = /"subagent-never-started"/;
// T4: 委托接线。
const RE_EXPORT = /export SubagentPrimitives\.\{/;
const RE_CALL_STARTSPY = /SubagentPrimitives\.startSpy\(/;
const RE_CALL_AWAIT = /SubagentPrimitives\.awaitResult\(/;
const RE_CALL_REAP = /SubagentPrimitives\.reap\(/;
// T5: 行为闸四腿标题（文本面在场）。
const RE_SPEC_LEGS = [/腿① 帧匹配/, /腿② 预算耗尽/, /腿③ 超时/, /腿④ reap/];
const RE_SPEC_ON_POINT = /SubagentPrimitives\./;

// T6: 合成样本——让零命中读数可信。
const PROBES = [
  [RE_DEFS['val NeverStartedToken'], '  val NeverStartedToken: String = "subagent-never-started"', 'NeverStartedToken def'],
  [RE_DEFS['val Timeout'], '  val Timeout: FiniteDuration = 15.minutes', 'Timeout def'],
  [RE_DEFS['def startBudget'], '  private[tools] def startBudget: FiniteDuration =', 'startBudget def'],
  [RE_DEFS['def awaitResult'], '  private[tools] def awaitResult(', 'awaitResult def'],
  [RE_DEFS['def reap'], '  private[tools] def reap(', 'reap def'],
  [RE_DEFS['def startSpy'], '  private[tools] def startSpy(', 'startSpy def'],
  [RE_DEFS['def spy'], '  private[tools] def spy(', 'spy def'],
  [RE_TOKEN_LITERAL, 'val NeverStartedToken: String = "subagent-never-started"', 'token literal'],
  [RE_EXPORT, '  export SubagentPrimitives.{Timeout, NeverStartedToken}', 'export clause'],
  [RE_CALL_STARTSPY, 'SubagentPrimitives.startSpy(started, base)', 'startSpy call'],
  [RE_CALL_AWAIT, 'SubagentPrimitives.awaitResult(started, done, childId)', 'awaitResult call'],
  [RE_CALL_REAP, 'SubagentPrimitives.reap(resources, parentId, childId, ref)', 'reap call'],
  [RE_SPEC_ON_POINT, 'SubagentPrimitives.spy(done, childId, base)', 'spec on point'],
];

const failures = [];
const oks = [];
let checks = 0;

function fresh(re) { return new RegExp(re.source); }
function occurrences(text, re) { const m = text.match(new RegExp(re.source, 'g')); return m ? m.length : 0; }
function ok(msg) { checks++; oks.push(msg); }
function bad(msg) { checks++; failures.push(msg); }

function readOrNull(path) {
  try { return readFileSync(path, 'utf8'); } catch { return null; }
}

function scalaFilesUnder(dir) {
  const out = [];
  (function walk(current) {
    for (const entry of readdirSync(current)) {
      const path = join(current, entry);
      const st = statSync(path);
      if (st.isDirectory()) walk(path);
      else if (entry.endsWith('.scala')) out.push(path);
    }
  })(dir);
  return out;
}

function run(root) {
  // ── SKIP 口径：不是 Stand 检出（src/main/scala/nebflow 缺席）⇒ 环境缺失 exit 2 ──
  if (!existsSync(join(root, 'src/main/scala/nebflow'))) {
    console.log(`[subagent-primitives] SKIP: not a Stand checkout (src/main/scala/nebflow missing under ${root}) — env error`);
    return 2;
  }

  // ── T6 probe-before-trust ─────────────────────────────────────────────────
  for (const [re, sample, label] of PROBES) {
    if (fresh(re).test(sample)) ok(`T6 ${label} detector hits its synthetic sample`);
    else bad(`T6 ${label} detector MISSES its synthetic sample (dead detector): ${sample.trim()}`);
  }

  // ── T1 scan face ──────────────────────────────────────────────────────────
  const texts = {};
  for (const [key, rel] of Object.entries(FILES)) {
    const text = readOrNull(join(root, rel));
    if (text === null) bad(`T1 scan face missing/unreadable: ${rel}`);
    else { texts[key] = text; ok(`T1 scan face present: ${rel}`); }
  }
  if (Object.keys(texts).length !== Object.keys(FILES).length) {
    return report();
  }

  // ── T2 单源（实现只在单点；消费点不含任何成员定义头）──────────────────────
  for (const [label, re] of Object.entries(RE_DEFS)) {
    const inPoint = occurrences(texts.primitives, re);
    const inConsumer = occurrences(texts.consumer, re);
    if (inPoint === 1 && inConsumer === 0) ok(`T2 ${label}: exactly-once in the single point, absent from the consumer`);
    else bad(`T2 ${label}: expected 1 in SubagentPrimitives and 0 in BlockingSubagent, got point=${inPoint} consumer=${inConsumer}`);
  }

  // ── T3 无重复 token 字面量（全域恰一处）────────────────────────────────────
  const literalHits = [
    ...scalaFilesUnder(join(root, 'src/main/scala')).map(file => [relative(root, file).split(sep).join('/'), readOrNull(file) ?? '']),
  ].filter(([, text]) => occurrences(text, RE_TOKEN_LITERAL) > 0);
  const totalLiteral = literalHits.reduce((sum, [, text]) => sum + occurrences(text, RE_TOKEN_LITERAL), 0);
  if (totalLiteral === 1 && literalHits.length === 1 && literalHits[0][0] === FILES.primitives) {
    ok(`T3 token literal exactly-once in src/main/scala: ${FILES.primitives}`);
  } else {
    bad(
      `T3 token literal "subagent-never-started" must appear exactly once in src/main/scala (single source), ` +
        `got ${totalLiteral} in ${literalHits.map(([file]) => file).join(', ') || 'none'}`
    );
  }

  // ── T4 委托接线 ───────────────────────────────────────────────────────────
  if (occurrences(texts.consumer, RE_EXPORT) === 1) ok(`T4 consumer re-exports the reuse surface (1x export SubagentPrimitives.{…})`);
  else bad(`T4 consumer must re-export the reuse surface exactly once (export SubagentPrimitives.{…})`);
  for (const [label, re] of [['startSpy', RE_CALL_STARTSPY], ['awaitResult', RE_CALL_AWAIT], ['reap', RE_CALL_REAP]]) {
    if (occurrences(texts.consumer, re) === 1) ok(`T4 consumer delegates ${label} to the single point (1x)`);
    else bad(`T4 consumer must call SubagentPrimitives.${label}( exactly once (delegation wiring)`);
  }

  // ── T5 行为闸四腿在场（文本面，非行为断言）─────────────────────────────────
  for (const re of RE_SPEC_LEGS) {
    if (fresh(re).test(texts.spec)) ok(`T5 behavior spec declares a leg: ${re.source}`);
    else bad(`T5 behavior spec lost a leg declaration: ${re.source}`);
  }
  if (fresh(RE_SPEC_ON_POINT).test(texts.spec)) ok('T5 behavior spec asserts on the single point (SubagentPrimitives)');
  else bad('T5 behavior spec must assert on SubagentPrimitives (the extracted single point)');

  return report();
}

function report() {
  if (failures.length === 0) {
    console.log(`[subagent-primitives] ${checks} checks — extraction surface + single source intact  [OK]`);
    for (const line of oks) console.log(`  ✓ ${line}`);
    return 0;
  }
  console.error(`[subagent-primitives] ${failures.length} contract violation(s):`);
  for (const line of failures) console.error(`  ✗ ${line}`);
  console.error(
    '\n修复口径：子会话原语的实现必须只在 SubagentPrimitives.scala 一处（无第二份复制 / 无重复 token ' +
      '字面量），BlockingSubagent 经 export + 调用点委托；四腿的行为断言在 SubagentPrimitiveSpec.scala。'
  );
  return 1;
}

if (invokedAsCli()) {
  try {
    process.exitCode = run(parseArgs(process.argv.slice(2)).root);
  } catch (error) {
    console.error(`[subagent-primitives] ERROR: ${error.message}`);
    process.exitCode = 2;
  }
}

export { run };
