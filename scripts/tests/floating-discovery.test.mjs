import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

test('missing configured baseline is a clear nonzero failure', () => {
  const result = spawnSync(process.execPath, ['scripts/check-floating-discovery.mjs'], { encoding: 'utf8', env: { ...process.env, FLOAT_DISCOVERY_BASE: 'missing-float-baseline-ref' } });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /baseline unavailable.*FLOAT_DISCOVERY_BASE/);
});

for (const [name, kind, input, diagnostic] of [
  ['fixed in another stylesheet', 'css', '.new-chrome { position: fixed; bottom: 0 }', /positioned selector/],
  ['absolute', 'css', '.new-chrome { position: absolute; top: 0 }', /positioned selector/],
  ['sticky', 'css', '.new-chrome { position: sticky; top: 0 }', /positioned selector/],
  ['dynamic insertion', 'js', "const bar = document.createElement('nav');", /dynamic DOM construction/],
]) test(`checker exits 1 for new ${name}`, () => {
  const dir = mkdtempSync(join(tmpdir(), 'float-discovery-'));
  try {
    const base = join(dir, 'base'), changed = join(dir, 'changed');
    writeFileSync(base, ''); writeFileSync(changed, input);
    const result = spawnSync(process.execPath, ['scripts/check-floating-discovery.mjs', '--pair', kind, base, changed], { encoding: 'utf8' });
    assert.equal(result.status, 1); assert.match(result.stderr, diagnostic);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
