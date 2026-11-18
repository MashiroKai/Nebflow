#!/usr/bin/env node
// Approval-gate invariants (2026-11-18, permission-timeout-narrow-gate):
//   1. the approval TRIGGER must come from the operation-risk classifier, never from
//      a file-name literal (a bare name in command text is data, not risk);
//   2. every permission wait must carry a timeout (fail-closed, never an unbounded wait).
// Source-level assertions: restoring the over-broad rule goes red here.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const ROOT = fileURLToPath(new URL('..', import.meta.url));

const GATE = 'src/main/scala/nebflow/core/tools/AgentInstructionWriteGate.scala';
const RISK = 'src/main/scala/nebflow/core/tools/CommandRisk.scala';
const CORE = 'src/main/scala/nebflow/agent/AgentCore.scala';

// The surviving bounded-wait primitive (permission-landing batch, P1) reads its deadline
// from this env override; the risk-gate branch's `-Dstand.permission.timeout.seconds`
// prop was superseded by the permission-landing implementation, so the invariant is
// re-anchored here to the implementation that actually ships (same criterion: the wait
// deadline must be configurable, never a bare literal and never unbounded).
const TIMEOUT_KEY = 'NEBFLOW_PERMISSION_WAIT_TIMEOUT_SEC';
// The removed rule exempted a hard-coded list of read-only commands. A whitelist of
// command names is not a risk criterion and must not come back.
const READ_ONLY_VERBS = ['cat', 'less', 'more', 'head', 'tail', 'grep', 'rg', 'test', 'stat', 'readlink'];
const MANY_TAIL = /(?:\w+\|){2,}/;

export function readOnlyWhitelist(source) {
  return source.split('\n')
    .filter(line => MANY_TAIL.test(line))
    .filter(line => READ_ONLY_VERBS.filter(verb => new RegExp(`\\b${verb}\\b`).test(line)).length >= 2);
}

// A definition block: a `def` at two-space indent, ending at the next one. Scala 3
// indentation syntax makes brace matching unusable, so blocks are split by header.
export function defBlocks(source) {
  const lines = source.split('\n');
  const heads = lines.flatMap((line, i) => (/^ {2}(?:private\s+)?def\s+\w+/.test(line) ? [i] : []));
  return heads.map((start, n) => ({
    header: lines[start].trim(),
    body: lines.slice(start, n + 1 < heads.length ? heads[n + 1] : lines.length).join('\n'),
  }));
}

// The invariants are about code: strip block comments and whole-line comments so a
// doc sentence may name a file without tripping the gate. A trailing `//` comment is
// left in place on purpose (stripping it could truncate a code line containing `//`).
export function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .filter(line => !/^\s*\/\//.test(line))
    .join('\n');
}

export function auditTrigger(gate, risk, files = {}) {
  const problems = [];
  const name = key => files[key] ?? key;
  const gateCode = stripComments(gate);
  const riskCode = stripComments(risk);
  if (!/CommandRisk\./.test(gateCode)) problems.push(`${name(GATE)}: approval trigger never consults the CommandRisk classifier`);
  // File-name literals may only describe a *target*; the risk verdict itself must not
  // be keyed on a document name.
  if (/\.md\b/i.test(riskCode)) problems.push(`${name(RISK)}: risk classifier is keyed on a document file-name literal`);
  for (const [label, source] of [[GATE, gateCode], [RISK, riskCode]]) {
    for (const line of readOnlyWhitelist(source)) {
      problems.push(`${name(label)}: read-only command whitelist is not a risk criterion: ${line.trim()}`);
    }
  }
  // Every function that judges an opaque command string must derive that verdict from
  // the risk classifier — target ∧ risk, never target alone.
  const opaque = defBlocks(gateCode).filter(block => /\(\s*command\s*:\s*String/.test(block.header));
  if (!opaque.length) problems.push(`${name(GATE)}: no opaque-command predicate found (expected a \`command: String\` parameter)`);
  for (const block of opaque) {
    if (!/CommandRisk\.isRisky\(/.test(block.body)) {
      problems.push(`${name(GATE)}: opaque-command verdict ignores the risk classifier: ${block.header}`);
    }
  }
  return problems;
}

export function auditWaitTimeout(core, files = {}) {
  const problems = [];
  const name = key => files[key] ?? key;
  const coreCode = stripComments(core);
  if (!coreCode.includes(TIMEOUT_KEY)) problems.push(`${name(CORE)}: no configurable permission-timeout key (${TIMEOUT_KEY})`);
  const waits = defBlocks(coreCode).filter(block => /^def (await\w*Decision)/.test(block.header));
  if (waits.length < 2) problems.push(`${name(CORE)}: expected both permission waits, found ${waits.length}`);
  for (const block of waits) {
    if (!/\.(timeoutTo|timeout)\(/.test(block.body)) {
      problems.push(`${name(CORE)}: permission wait without a timeout: ${block.header}`);
    }
  }
  if (!/permissionWaitTimeoutMs\s*:\s*Long/.test(coreCode)) {
    problems.push(`${name(CORE)}: permission timeout has no named accessor`);
  }
  return problems;
}

export function audit({ gate, risk, core, files = {} }) {
  return [...auditTrigger(gate, risk, files), ...auditWaitTimeout(core, files)];
}

function main() {
  const texts = Object.fromEntries([GATE, RISK, CORE].map(rel => [rel, readFileSync(resolve(ROOT, rel), 'utf8')]));
  const problems = audit({ gate: texts[GATE], risk: texts[RISK], core: texts[CORE], files: texts });
  if (problems.length) {
    console.error(problems.map(problem => `FAIL ${problem}`).join('\n'));
    process.exit(1);
  }
  console.log('permission-gate: trigger is risk-based and every permission wait has a timeout');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
