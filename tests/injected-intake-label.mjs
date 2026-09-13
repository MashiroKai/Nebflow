#!/usr/bin/env node
// mailbadge batch (2026-09-13, option C) — injected-bubble label behavioral gate.
//
// Runs the REAL `web/js/chat.js` label logic (extracted, not re-implemented) in a
// node VM sandbox: positive (intake drives the label), negative control / fallback
// (field absent ⇒ the pre-change rendering, byte-identical), reverse cases (Task /
// Dispatch / non-project Mail unchanged) and a mutation check (dropping the intake
// priority must turn the positive case RED — proves the gate can fail).
//
// Usage: node tests/injected-intake-label.mjs   (repo root; no browser, no server)
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const chatJsPath = join(repoRoot, 'src', 'main', 'resources', 'web', 'js', 'chat.js');
const src = readFileSync(chatJsPath, 'utf8');

/** Extract `INJECTED_SOURCE_LABELS` … `injectedSourceLabel` (one contiguous range:
 *  the table, EVENT_TYPE_LABELS, NODE_STATUS_LABELS, nodeStatusLabel, the function). */
function extractLabelLogic(source) {
  const start = source.indexOf('const INJECTED_SOURCE_LABELS');
  if (start < 0) throw new Error('chat.js: INJECTED_SOURCE_LABELS not found (moved/renamed?)');
  const fnStart = source.indexOf('export function injectedSourceLabel');
  if (fnStart < 0) throw new Error('chat.js: injectedSourceLabel not found (moved/renamed?)');
  const nextExport = source.indexOf('\nexport ', fnStart + 1);
  const fnEnd = nextExport < 0 ? source.length : nextExport;
  const body = source.slice(start, fnEnd).replace('export function injectedSourceLabel', 'function injectedSourceLabel');
  return `${body}\nexport { injectedSourceLabel, INJECTED_SOURCE_LABELS };`;
}

/** Evaluate the extracted label logic and hand back its public surface. */
function labelFnFor(code) {
  const cjs = code.replace(/^export \{.*$/m, '') + '\nreturn { injectedSourceLabel, INJECTED_SOURCE_LABELS };';
  // eslint-disable-next-line no-new-func
  return new Function(cjs)();
}

const labelCode = extractLabelLogic(src);
const { injectedSourceLabel: label, INJECTED_SOURCE_LABELS: table } = labelFnFor(labelCode);

const results = [];
function check(name, got, want) {
  const ok = got === want;
  results.push({ name, ok, got, want });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}\n        got=${JSON.stringify(got)} want=${JSON.stringify(want)}`);
}

// ── 1. positive: project-face Mail (source stays 'task', intake='mail') ──
check('P1 project-face Mail: source=task + intake=mail ⇒ Mail label',
  label('task', 'info', 'Nebula', undefined, 'mail'), 'Mail · Nebula · Info');

// ── 2. negative control: field absent ⇒ pre-change rendering (fallback path) ──
check('N1 no intake: source=task ⇒ Task label (byte-identical to before)',
  label('task', 'info', 'Nebula', undefined, undefined), 'Task · Nebula · Info');
check('N2 no intake argument at all (old callers) ⇒ Task label',
  label('task', 'info', 'Nebula'), 'Task · Nebula · Info');

// ── 3. reverse cases (author's criterion 2) ──
check('R1 Task-tool receipt (source=task, no intake) ⇒ Task',
  label('task', undefined, 'Nebula', undefined, undefined), 'Task · Nebula');
check('R2 dispatch reflow notice (source=dispatch) ⇒ Dispatch',
  label('dispatch', undefined, 'project-dispatcher', undefined, undefined), 'Dispatch · project-dispatcher');
check('R3 non-project Mail (source=mail, no intake) ⇒ Mail (unchanged)',
  label('mail', 'result', 'project-dispatcher', undefined, undefined), 'Mail · project-dispatcher · Result');
check('R4 node receipt face (source=system, no intake) ⇒ System (untouched)',
  label('system', undefined, 'Nebula', undefined, undefined), 'System · Nebula');
check('R5 team path unaffected by intake',
  label('task', 'result', 'Backend', 'nebflow-project', 'mail'), 'nebflow-project/Backend · Result');

// ── 4. registration discipline: intake vocabulary must be explicitly registered ──
const backEndIntake = ['mail']; // = InjectionAttribution.IntakeMarkers
for (const v of backEndIntake) {
  check(`G1 intake '${v}' explicitly registered in INJECTED_SOURCE_LABELS`, table[v], v === 'mail' ? 'Mail' : undefined);
}

// ── 5. mutation: remove the intake priority ⇒ positive case must go RED ──
const mutated = labelCode.replace('const key = intake || source;', 'const key = source;');
if (mutated === labelCode) {
  results.push({ name: 'M0 mutation anchor present', ok: false, got: 'anchor missing', want: 'const key = intake || source;' });
  console.log('FAIL  M0 mutation anchor `const key = intake || source;` not found in chat.js — gate would be inert');
} else {
  const mLabel = labelFnFor(mutated).injectedSourceLabel;
  const mutatedOut = mLabel('task', 'info', 'Nebula', undefined, 'mail');
  check('M1 mutated (intake priority removed) positive case turns RED',
    mutatedOut !== 'Mail · Nebula · Info', true);
  console.log(`        (mutation produced ${JSON.stringify(mutatedOut)} — the gate is not inert)`);
}

const failed = results.filter(r => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
if (failed.length) {
  console.log('FAILED: ' + failed.map(f => f.name).join(' | '));
  process.exit(1);
}
console.log('OK — injected-bubble intake label gate green');
