import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { parseCompilerResult, compareBaseline, runTsc } from '../check-js-types.mjs';

const diagnostic = "src/example.js(1,2): error TS2339: Property 'x' does not exist.\n";
const result = (status, stdout = '', extra = {}) => ({ status, stdout, stderr: '', ...extra });

test('clean success and recognized diagnostics are accepted', () => {
  assert.deepEqual(parseCompilerResult(result(0)), { files: {}, total: 0 });
  for (const status of [1, 2]) {
    assert.deepEqual(parseCompilerResult(result(status, diagnostic + "  Type 'string' is not assignable to type 'number'.\n")), {
      files: { 'src/example.js': { TS2339: 1 } }, total: 1,
    });
  }
});

test('compiler and configuration failures fail closed', () => {
  for (const value of [
    result(1), result(2, 'unknown output'), result(0, diagnostic),
    result(2, "error TS18003: No inputs were found in config file.\n"),
    result(2, 'jsconfig.json(2,1): error TS5023: Unknown compiler option.\n'),
    result(2, 'jsconfig.json(2,1): error TS1005: Missing comma.\n'),
    result(2, 'jsconfig.json(2,1): error TS10001: Invalid config.\n'),
    result(2, diagnostic + 'unexpected output\n'),
    result(2, diagnostic + '  unexpected indented output\n'),
    result(2, diagnostic, { stderr: 'compiler crashed' }),
    result(3, diagnostic), result(null), result(0, '', { signal: 'SIGTERM' }),
    result(0, '', { error: new Error('spawn ENOENT') }),
    result(null, '', { error: Object.assign(new Error('timed out'), { code: 'ETIMEDOUT' }) }),
  ]) assert.throws(() => parseCompilerResult(value));
});

test('baseline comparison retains count, new-file and zero-error gates', () => {
  const current = parseCompilerResult(result(2, diagnostic));
  assert.deepEqual(compareBaseline(current, current), []);
  assert.deepEqual(compareBaseline({ files: {}, total: 0 }, current), []);
  assert.ok(compareBaseline(current, { files: {}, total: 0 }).length);
  assert.ok(compareBaseline(parseCompilerResult(result(2, diagnostic.repeat(2))), current).length);
  const core = parseCompilerResult(result(2, diagnostic.replace('src/example.js', 'src/main/resources/web/js/state.js')));
  assert.ok(compareBaseline(core, core).length);
});

test('real compiler subprocess handles success, diagnostic exit, crash, signal and timeout', () => {
  const directory = mkdtempSync(join(tmpdir(), 'type-gate-test-'));
  const compiler = join(directory, 'compiler.cjs');
  try {
    for (const [source, succeeds] of [
      ['', true],
      [`process.stdout.write(${JSON.stringify(diagnostic)}); process.exitCode = 2;`, true],
      ["throw new Error('crash')", false],
      ["process.kill(process.pid, 'SIGTERM')", false],
      ['setInterval(() => {}, 1000)', false],
      ['process.exitCode = 2', false],
    ]) {
      writeFileSync(compiler, source);
      const invoke = () => runTsc({ compiler, cwd: directory, timeout: source.includes('setInterval') ? 150 : 2000 });
      if (succeeds) assert.doesNotThrow(invoke);
      else assert.throws(invoke);
    }
    assert.throws(() => runTsc({ compiler: join(directory, 'missing') }), /missing/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
