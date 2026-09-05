#!/usr/bin/env node
// check-js-types.mjs — A1 checkJs gate: tsc --noEmit vs a frozen baseline.
//
// Runs tsc over jsconfig.json (checkJs + allowJs, strict off) and compares
// the error set against tests/type-baseline.json. The comparison is
// count-based per (file, TS code) — robust to line-number drift when files
// are edited, and it enforces the roadmap rule "baseline only goes down":
// any NEW file with errors, any (file, code) count increase, or any total
// increase fails the gate.
//
// Files in ZERO_ERROR_FILES (P2-3 core contracts) must be clean outright —
// baseline entries for them are ignored and any error there is a failure.
//
// Usage:
//   node scripts/check-js-types.mjs              # check (CI)
//   node scripts/check-js-types.mjs --update     # regenerate baseline after intentional fixes
//
// tsc resolution: local node_modules/typescript ONLY (typescript is pinned as
// a devDependency). The old `npx -y -p typescript@5.5` fallback was removed:
// in sandboxed environments it hits EPERM on the npm cache and silently
// produced untrustworthy (false-PASS) gate results. Without local tsc this
// script now fails loudly with a `npm install` hint.

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const BASELINE_PATH = join(ROOT, 'tests', 'type-baseline.json');
const TSC_LOCAL = join(ROOT, 'node_modules', 'typescript', 'bin', 'tsc');

// P2-3: core contract files must have ZERO checkJs errors. Baseline rows for
// these files are ignored; any error here fails the gate outright.
const ZERO_ERROR_FILES = [
  // P2-3 core contracts — typed, and must never regress:
  'src/main/resources/web/js/state.js',
  'src/main/resources/web/js/ws.js',
  'src/main/resources/web/js/utils.js',
];

function runTsc() {
  const args = ['-p', 'jsconfig.json', '--pretty', 'false'];
  if (!existsSync(TSC_LOCAL)) {
    // No silent npx fallback: an unresolvable tsc must fail the gate loudly.
    // (npx fallback removed 2026-09-05 — see header comment.)
    console.error(
      'checkJs gate: TypeScript not found at node_modules/typescript.\n' +
      '  Fix: npm install   (typescript is a pinned devDependency)\n' +
      '  This gate no longer falls back to npx: that path silently produced\n' +
      '  untrustworthy results when the npm cache was not writable.'
    );
    process.exit(2);
  }
  try {
    return execFileSync(process.execPath, [TSC_LOCAL, ...args], { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (e) {
    // tsc exits 2 when errors are found — stdout still carries the report.
    if (e.stdout != null) return e.stdout;
    throw e;
  }
}

/** Parse tsc output into { file: { code: count } } plus a total. */
function parseErrors(output) {
  const files = {};
  let total = 0;
  for (const line of output.split('\n')) {
    const m = line.match(/^(.+?)\(\d+,\d+\): error (TS\d+):/);
    if (!m) continue;
    const [, file, code] = m;
    (files[file] ??= {})[code] = (files[file][code] ?? 0) + 1;
    total++;
  }
  return { files, total };
}

const { files, total } = parseErrors(runTsc());

if (process.argv.includes('--update')) {
  const baseline = { generatedBy: 'scripts/check-js-types.mjs --update', total, files };
  writeFileSync(BASELINE_PATH, JSON.stringify(baseline, null, 2) + '\n');
  console.log(`baseline written: ${total} errors across ${Object.keys(files).length} files → tests/type-baseline.json`);
  process.exit(0);
}

if (!existsSync(BASELINE_PATH)) {
  console.error('no baseline found — run: node scripts/check-js-types.mjs --update');
  process.exit(1);
}
const baseline = JSON.parse(readFileSync(BASELINE_PATH, 'utf8'));

const failures = [];
const improvements = [];

// Zero-error files: hard gate, baseline rows ignored.
for (const f of ZERO_ERROR_FILES) {
  const count = Object.values(files[f] ?? {}).reduce((a, b) => a + b, 0);
  if (count > 0) failures.push(`${f}: ${count} errors — this file is a P2-3 core contract and must be checkJs-clean`);
}

// New files with errors (not in baseline at all, excluding zero-gate files).
for (const [file, codes] of Object.entries(files)) {
  if (ZERO_ERROR_FILES.includes(file)) continue;
  if (!baseline.files[file]) {
    failures.push(`${file}: new file with ${Object.values(codes).reduce((a, b) => a + b, 0)} errors — new/touched files must be checkJs-clean`);
  }
}

// Per (file, code) count increases.
for (const [file, codes] of Object.entries(files)) {
  if (ZERO_ERROR_FILES.includes(file) || !baseline.files[file]) continue;
  for (const [code, count] of Object.entries(codes)) {
    const base = baseline.files[file][code] ?? 0;
    if (count > base) failures.push(`${file}: ${code} count ${count} > baseline ${base}`);
  }
}

// Total increase (redundant with the above in most cases, but cheap and explicit).
const baselineEffectiveTotal = baseline.total
  - ZERO_ERROR_FILES.reduce((acc, f) => acc + Object.values(baseline.files[f] ?? {}).reduce((a, b) => a + b, 0), 0);
const currentEffectiveTotal = total
  - ZERO_ERROR_FILES.reduce((acc, f) => acc + Object.values(files[f] ?? {}).reduce((a, b) => a + b, 0), 0);
if (currentEffectiveTotal > baselineEffectiveTotal) {
  failures.push(`total errors ${currentEffectiveTotal} > baseline ${baselineEffectiveTotal}`);
}

// Improvements (informational — encourages burning the baseline down).
const currentTotalAll = total;
if (currentTotalAll < baseline.total) {
  improvements.push(`total errors ${currentTotalAll} < baseline ${baseline.total} (-${baseline.total - currentTotalAll}) — consider --update to burn the baseline down`);
}

console.log(`checkJs: ${total} errors (baseline ${baseline.total}, zero-gate files: ${ZERO_ERROR_FILES.length})`);
for (const i of improvements) console.log(`  OK  ${i}`);
if (failures.length) {
  console.error('\ncheckJs GATE FAILURES:');
  for (const f of failures) console.error(`  ${f}`);
  process.exit(1);
}
console.log('checkJs PASS: no new errors above baseline.');
