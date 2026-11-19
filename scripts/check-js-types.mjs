#!/usr/bin/env node
// Compare recognized checkJs diagnostics with a count-only, downward baseline.
// Compiler/configuration/process failures must never become an empty error set.

import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join, resolve } from 'node:path';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const BASELINE_PATH = join(ROOT, 'tests', 'type-baseline.json');
const TSC_LOCAL = join(ROOT, 'node_modules', 'typescript', 'bin', 'tsc');
const ZERO_ERROR_FILES = [
  'src/main/resources/web/js/state.js',
  'src/main/resources/web/js/ws.js',
  'src/main/resources/web/js/utils.js',
];

/** Accept only clean success or a wholly recognized source-diagnostic failure. */
export function parseCompilerResult(result) {
  if (result.error) throw new Error(`TypeScript process failed: ${result.error.message}`);
  if (result.signal) throw new Error(`TypeScript terminated by ${result.signal}`);
  if (!Number.isInteger(result.status)) throw new Error('TypeScript returned no exit status');
  if ((result.stderr ?? '').trim()) throw new Error(`Unexpected TypeScript stderr: ${result.stderr}`);
  const output = result.stdout ?? '';
  if (result.status === 0) {
    if (output.trim()) throw new Error(`Unexpected output from successful TypeScript: ${output}`);
    return { files: {}, total: 0 };
  }
  if (![1, 2].includes(result.status)) throw new Error(`Unexpected TypeScript exit status: ${result.status}`);
  const files = {};
  let total = 0;
  for (const line of output.split(/\r?\n/)) {
    if (!line.trim()) continue;
    const match = line.match(/^(.+?)\(\d+,\d+\): error (TS(\d+)): .+$/);
    if (!match) {
      // tsc indents explanatory continuation lines after a source diagnostic.
      if (total > 0 && /^\s+(?:Type |Types of property |Property |The types of |Index signature for type |Target signature provides too few arguments)/.test(line) && !/error TS\d+/.test(line)) continue;
      throw new Error(`Unrecognized TypeScript output: ${line}`);
    }
    const [, file, code, number] = match;
    if (!/\.[cm]?[jt]sx?$/.test(file) || (Number(number) >= 5000 && Number(number) < 7000) || Number(number) >= 10000) {
      throw new Error(`TypeScript configuration failure: ${line}`);
    }
    (files[file] ??= {})[code] = (files[file][code] ?? 0) + 1;
    total++;
  }
  if (total === 0) throw new Error(`TypeScript failed (${result.status}) without source diagnostics`);
  return { files, total };
}

export function runTsc({ compiler = TSC_LOCAL, cwd = ROOT, timeout = 120_000 } = {}) {
  if (!existsSync(compiler)) throw new Error('Pinned TypeScript is missing; run npm ci first');
  return parseCompilerResult(spawnSync(process.execPath, [compiler, '-p', 'jsconfig.json', '--pretty', 'false'], {
    cwd, encoding: 'utf8', timeout, killSignal: 'SIGKILL', maxBuffer: 16 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'pipe'],
  }));
}

export function compareBaseline({ files, total }, baseline) {
  const failures = [];
  const count = codes => Object.values(codes ?? {}).reduce((a, b) => a + b, 0);
  for (const file of ZERO_ERROR_FILES) {
    if (count(files[file]) > 0) failures.push(`${file}: core contract must be checkJs-clean`);
  }
  for (const [file, codes] of Object.entries(files)) {
    if (ZERO_ERROR_FILES.includes(file)) continue;
    if (!baseline.files[file]) failures.push(`${file}: new file with ${count(codes)} errors`);
    else {
      for (const [code, current] of Object.entries(codes)) {
        const previous = baseline.files[file][code] ?? 0;
        if (current > previous) failures.push(`${file}: ${code} count ${current} > baseline ${previous}`);
      }
    }
  }
  const effective = value => value.total - ZERO_ERROR_FILES.reduce((sum, file) => sum + count(value.files[file]), 0);
  if (effective({ files, total }) > effective(baseline)) {
    failures.push(`total errors ${effective({ files, total })} > baseline ${effective(baseline)}`);
  }
  return failures;
}

export function main(args = process.argv.slice(2)) {
  if (args.some(arg => arg !== '--update') || args.length > 1) throw new Error('Usage: check-js-types.mjs [--update]');
  const diagnostics = runTsc();
  const { files, total } = diagnostics;
  if (args.includes('--update')) {
    writeFileSync(BASELINE_PATH, JSON.stringify({ generatedBy: 'scripts/check-js-types.mjs --update', total, files }, null, 2) + '\n');
    console.log(`baseline written: ${total} errors across ${Object.keys(files).length} files`);
    return 0;
  }
  const baseline = JSON.parse(readFileSync(BASELINE_PATH, 'utf8'));
  const failures = compareBaseline(diagnostics, baseline);
  console.log(`checkJs: ${total} errors (baseline ${baseline.total}, zero-gate files: ${ZERO_ERROR_FILES.length})`);
  if (total < baseline.total) console.log(`Improvement: ${baseline.total - total} fewer errors; baseline may be reduced after review`);
  for (const failure of failures) console.error(`  FAIL ${failure}`);
  if (!failures.length) console.log('checkJs PASS: no new errors above baseline.');
  return failures.length ? 1 : 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { process.exitCode = main(); }
  catch (error) { console.error(`checkJs ERROR: ${error.message}`); process.exitCode = 2; }
}
