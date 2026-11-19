import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import vm from 'node:vm';

// Execute the actual success-cleanup leg with a disposable override pointing at
// a shared tree. The legacy revision is supplied explicitly for a red run.
test('success cleanup preserves an override and its foreign batch', async () => {
  const root = mkdtempSync(join(tmpdir(), 'cfg-cleanup-regression-'));
  try {
    const evidence = join(root, 'shared');
    const foreign = join(evidence, 'other-project', 'batch');
    const outside = join(root, 'outside');
    mkdirSync(foreign, { recursive: true });
    mkdirSync(outside);
    writeFileSync(join(foreign, 'payload'), 'foreign');
    writeFileSync(join(outside, 'payload'), 'outside');
    symlinkSync(outside, join(evidence, 'link'));
    const source = process.env.EVIDENCE_TEST_REVISION
      ? execFileSync('git', ['show', `${process.env.EVIDENCE_TEST_REVISION}:scripts/e2e-config-single-source.mjs`], { encoding: 'utf8' })
      : readFileSync(new URL('../scripts/e2e-config-single-source.mjs', import.meta.url), 'utf8');
    const start = source.indexOf('  await stopGateway(HOME);\n  log(`\\n[e2e]');
    const end = source.indexOf('  process.exit(failures === 0 ? 0 : 1);', start);
    assert(start >= 0 && end > start, 'cleanup leg must be found');
    const run = vm.runInNewContext(`(async () => {\n${source.slice(start, end)}\n})`, {
      stopGateway: async () => {}, HOME: root, EVID: evidence, failures: 0,
      log: () => {}, rmSync,
    });
    await run();
    assert.equal(readFileSync(join(foreign, 'payload'), 'utf8'), 'foreign');
    assert.equal(readFileSync(join(outside, 'payload'), 'utf8'), 'outside');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
