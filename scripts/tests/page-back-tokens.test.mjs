import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

test('back gate rejects literal dimensions and colors, accepts tokens', () => {
  const dir = mkdtempSync(join(tmpdir(), 'page-back-test-'));
  try {
    mkdirSync(join(dir, 'css'));
    const css = join(dir, 'css', 'fixture.css');
    const run = () => spawnSync(process.execPath, [resolve('scripts/check-page-back-tokens.mjs')], { env: { ...process.env, STAND_PAGE_BACK_WEB: dir }, encoding: 'utf8' });
    writeFileSync(css, '.sample-back { width: 36px; color: #fff; }');
    assert.equal(run().status, 1);
    writeFileSync(css, '.sample-back { width: var(--ds-space-8); color: var(--color-text); }');
    assert.equal(run().status, 0);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
