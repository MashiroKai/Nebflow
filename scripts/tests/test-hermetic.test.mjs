import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('../check-test-hermetic.mjs', import.meta.url));
test('new violations fail, baseline tolerates only existing occurrences', () => {
  const dir = mkdtempSync(join(tmpdir(), 'hermetic-check-'));
  try {
    const src = join(dir, 'src'); mkdirSync(src);
    const file = join(src, 'Example.scala');
    const baseline = join(dir, 'baseline.json');
    writeFileSync(baseline, JSON.stringify({ version: 1, entries: [] }));
    writeFileSync(file, 'Thread.sleep(20)\n');
    const run = (...args) => spawnSync(process.execPath, [script, ...args, src, baseline], { encoding: 'utf8' });
    const red = run(); assert.equal(red.status, 1); assert.match(red.stdout, /Example.scala:1: sleep:/);
    assert.equal(run('--generate-baseline').status, 0);
    assert.equal(run().status, 0);
    writeFileSync(file, 'Thread.sleep(20)\nThread.sleep(20)\n');
    assert.equal(run().status, 1);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
