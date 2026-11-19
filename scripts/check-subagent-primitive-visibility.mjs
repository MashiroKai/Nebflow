#!/usr/bin/env node
// check-subagent-primitive-visibility.mjs — dispatcher 重设计 P1「放开原语可见性」
// 的**外壳闸**（作者裁定 2026-11-19：P1 这一件只做可见性，**不引入任何读者**）。
//
// 背景：P0 把子会话原语（完成桥 `spy`/`startSpy`、两段等待 `awaitResult`/
// `startBudget`、终态回收 `reap`、结构化失败 token `NeverStartedToken`/`Timeout`）
// 抽成单点 `core/tools/SubagentPrimitives.scala`，但复用面里五个成员仍是
// `private[tools]` —— 跨不出 `nebflow.core.tools`；P1 的引擎侧读者落在
// `nebflow.core.project` / `nebflow.core.delegate`（核稿 §6.1「抽件 = 放宽可见性
// 或搬移」的另一半）。本件把那五个成员**恰好**放宽为 `private[core]`。
//
// 职责切分：本闸**只**断言「放宽面 + 无新读者」这一条轴。抽取面 / 单源 / 委托接线
// 仍归 `check-subagent-primitives.mjs`；行为断言归 P1 行为件（另派节点）——本闸不做
// 任何行为断言。
//
// ── 判据（任一不成立即红）────────────────────────────────────────────────
//  V1 scan face：单点在场。
//  V2 放宽面恰等：声明位挂 `private[core]` 的成员集合**恰** = {startBudget,
//     awaitResult, reap, startSpy, spy}（逐条枚举；不多不少；命中数 = 5，只增不降）。
//  V3 无 `private[tools]` 声明残留（复用面在单点里已全部放宽）。
//  V4 未收窄：Timeout / NeverStartedToken 保持无修饰（公开）——放宽不许顺手把
//     相邻成员收窄。
//  V5 目标面在授予域内：`nebflow/core/project` 与 `nebflow/core/delegate` 都在
//     `nebflow.core` 之下 ⇒ 授予域 `core` 确实覆盖 P1 的引擎侧读者位置。
//  V6 读者集合为空：全 `src/main/scala/nebflow/**` 里引用单点的文件集合**恰** =
//     {单点自身, `core/tools` 内的委托消费点}；`core/tools` 之外出现引用即越界
//     （那是 P1 行为件的范围——停下报作者）。
//  V7 probe-before-trust：每个检测器先在合成样本上命中 / 该漏的漏（零命中读数才可信）。
//
// Usage: node scripts/check-subagent-primitive-visibility.mjs [--root <dir>]
// Exit:  0 绿 / 1 红（放宽面漂移 / 出现读者）/ 2 环境缺失（SKIP：不是 Stand 检出）

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

const MAIN = 'src/main/scala/nebflow';
const FILES = {
  primitives: `${MAIN}/core/tools/SubagentPrimitives.scala`,
  consumer: `${MAIN}/core/tools/BlockingSubagent.scala`,
};
// V5：P1 的引擎侧读者落点（授予域 `core` 必须覆盖它们）。
const TARGET_FACE = [`${MAIN}/core/project`, `${MAIN}/core/delegate`];

// V2：被放宽的那一批 —— P1「第一件」的**全部**放宽面（不多不少）。
const RELAXED = ['startBudget', 'awaitResult', 'reap', 'startSpy', 'spy'];
// V4：原就公开、不得收窄的两个成员。
const STAYS_PUBLIC = ['Timeout', 'NeverStartedToken'];

// 声明位检测器（**锚在行首**：scaladoc 散文里的同名字符串不算声明）。
const RE_CORE_DECL = /^[ \t]*private\[core\][ \t]+(?:def|val|var)[ \t]+(\w+)/gm;
const RE_TOOLS_DECL = /^[ \t]*private\[tools\][ \t]+(?:def|val|var)[ \t]+(\w+)/gm;
const RE_PUBLIC_DECL = name => new RegExp(`^[ \\t]*(?:def|val|var)[ \\t]+${name}\\b`, 'm');
// V6：单点引用检测器。
const RE_POINT_REF = /SubagentPrimitives/;

const PROBES = [
  [RE_CORE_DECL, '  private[core] def awaitResult(', 'private[core] def decl', true],
  [RE_CORE_DECL, '  private[core] def startBudget: FiniteDuration =', 'private[core] def with type', true],
  [RE_TOOLS_DECL, '  private[tools] def reap(', 'private[tools] def decl', true],
  [RE_POINT_REF, 'SubagentPrimitives.awaitResult(a, b)', 'single-point reference', true],
  [RE_PUBLIC_DECL('Timeout'), '  val Timeout: FiniteDuration = 15.minutes', 'public val decl', true],
  [RE_PUBLIC_DECL('Timeout'), '  private[core] val Timeout: FiniteDuration = 15.minutes', 'narrowed val must MISS', false],
];

const failures = [];
const oks = [];
let checks = 0;

// Fresh instance per test: keeps `m` (line-anchored detectors need it) but drops `g`
// so a single-shot `.test()` can never be tripped by a stale `lastIndex`.
function fresh(re) { return new RegExp(re.source, re.flags.replace('g', '')); }
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

/** 逐条枚举某个声明位检测器命中的成员名（命中数 = 该集合的势）。 */
function memberNames(text, re) {
  const out = [];
  for (const m of text.matchAll(new RegExp(re.source, 'gm'))) out.push(m[1]);
  return out;
}

function isRelaxedExactly(text) {
  const names = memberNames(text, RE_CORE_DECL).sort();
  const expected = [...RELAXED].sort();
  return names.length === expected.length && names.every((n, i) => n === expected[i]);
}

function run(root) {
  // ── SKIP 口径：不是 Stand 检出（src/main/scala/nebflow 缺席）⇒ 环境缺失 exit 2 ──
  if (!existsSync(join(root, MAIN))) {
    console.log(`[subagent-primitive-visibility] SKIP: not a Stand checkout (${MAIN} missing under ${root}) — env error`);
    return 2;
  }

  // ── V7 probe-before-trust ─────────────────────────────────────────────────
  for (const [re, sample, label, shouldHit] of PROBES) {
    const hit = fresh(re).test(sample);
    if (hit === shouldHit) ok(`V7 ${label} detector ${shouldHit ? 'hits' : 'misses'} its synthetic sample`);
    else bad(`V7 ${label} detector is dead (expected ${shouldHit ? 'hit' : 'miss'}): ${sample.trim()}`);
  }

  // ── V1 scan face ──────────────────────────────────────────────────────────
  const point = readOrNull(join(root, FILES.primitives));
  const consumer = readOrNull(join(root, FILES.consumer));
  if (point === null) bad(`V1 scan face missing/unreadable: ${FILES.primitives}`);
  else ok(`V1 scan face present: ${FILES.primitives}`);
  if (consumer === null) bad(`V1 scan face missing/unreadable: ${FILES.consumer}`);
  else ok(`V1 scan face present: ${FILES.consumer}`);
  if (point === null) return report();

  // ── V2 放宽面恰等（可达面 = 授予域 `core`）────────────────────────────────
  const relaxed = memberNames(point, RE_CORE_DECL).sort();
  if (isRelaxedExactly(point)) {
    ok(`V2 relaxed surface is exactly the intended batch (${RELAXED.length}): ${relaxed.join(', ')}`);
  } else {
    bad(
      `V2 relaxed surface must be exactly [${[...RELAXED].sort().join(', ')}] via private[core] ` +
        `(nothing more, nothing less; the count may never drop), got [${relaxed.join(', ')}]`
    );
  }
  for (const name of RELAXED) {
    const decl = new RegExp(`^[ \\t]*private\\[core\\][ \\t]+(?:def|val|var)[ \\t]+${name}\\b`, 'm');
    if (decl.test(point)) ok(`V2 ${name} is reached from outside core.tools (private[core])`);
    else bad(`V2 ${name} is NOT relaxed (private[core] absent at its declaration)`);
  }

  // ── V3 无 private[tools] 声明残留 ─────────────────────────────────────────
  const stillTools = memberNames(point, RE_TOOLS_DECL);
  if (stillTools.length === 0) ok('V3 no private[tools] declaration survives in the single point');
  else bad(`V3 private[tools] declaration(s) still in the single point: ${stillTools.join(', ')}`);

  // ── V4 未收窄 ─────────────────────────────────────────────────────────────
  for (const name of STAYS_PUBLIC) {
    if (fresh(RE_PUBLIC_DECL(name)).test(point)) ok(`V4 ${name} stays modifier-free (public), not narrowed`);
    else bad(`V4 ${name} must stay modifier-free (public) — relaxation must not narrow a neighbour`);
  }

  // ── V5 目标面在授予域内 ───────────────────────────────────────────────────
  for (const face of TARGET_FACE) {
    const path = join(root, face);
    if (existsSync(path) && statSync(path).isDirectory()) ok(`V5 target reader face is under the granted scope: ${face}`);
    else bad(`V5 target reader face missing (scope \`core\` no longer covers it): ${face}`);
  }

  // ── V6 读者集合为空（本节点不许引入任何消费者）─────────────────────────────
  const referencing = scalaFilesUnder(join(root, MAIN))
    .filter(file => {
      const text = readOrNull(file);
      return text !== null && fresh(RE_POINT_REF).test(text);
    })
    .map(file => relative(root, file).split(sep).join('/'));
  const expectedRefs = [FILES.primitives, FILES.consumer].sort();
  const gotRefs = [...referencing].sort();
  if (gotRefs.length === expectedRefs.length && gotRefs.every((f, i) => f === expectedRefs[i])) {
    ok(`V6 referencing-file set is exactly the single point + its in-package consumer (${gotRefs.length}): ${gotRefs.join(', ')}`);
  } else {
    bad(
      `V6 referencing-file set must be exactly [${expectedRefs.join(', ')}] (this node introduces no reader), ` +
        `got [${gotRefs.join(', ')}]`
    );
  }
  const outside = gotRefs.filter(f => !f.startsWith(`${MAIN}/core/tools/`));
  if (outside.length === 0) ok('V6 reader set outside core/tools is empty (no consumer introduced by this node)');
  else bad(`V6 out-of-bounds reader(s) outside core/tools (that is the P1 behaviour node's scope): ${outside.join(', ')}`);

  return report();
}

function report() {
  if (failures.length === 0) {
    console.log(`[subagent-primitive-visibility] ${checks} checks — relaxation surface + no-reader fence intact  [OK]`);
    for (const line of oks) console.log(`  ✓ ${line}`);
    return 0;
  }
  console.error(`[subagent-primitive-visibility] ${failures.length} contract violation(s):`);
  for (const line of failures) console.error(`  ✗ ${line}`);
  console.error(
    '\n修复口径：复用面五个成员（startBudget / awaitResult / reap / startSpy / spy）必须恰好放宽为 ' +
      '`private[core]`（能覆盖 `nebflow.core.project` / `nebflow.core.delegate` 的最小授予域），不得更宽也不得收窄；' +
      'Timeout / NeverStartedToken 保持公开；本节点不引入任何读者（core/tools 之外零引用单点）。'
  );
  return 1;
}

if (invokedAsCli()) {
  try {
    process.exitCode = run(parseArgs(process.argv.slice(2)).root);
  } catch (error) {
    console.error(`[subagent-primitive-visibility] ERROR: ${error.message}`);
    process.exitCode = 2;
  }
}

export { run };
