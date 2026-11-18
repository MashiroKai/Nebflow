import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { check } from '../check-floating-clearance.mjs';

const css = readFileSync(new URL('../../src/main/resources/web/css/mobileLayer.css', import.meta.url), 'utf8');
test('current mobile contract passes', () => assert.deepEqual(check(css), []));
for (const [name, bad, expected] of [
  ['absolute dock', '#mobile-dock { position: absolute; }', /floating chrome/],
  ['viewport height', '.content { height: 100vh; }', /100vh/],
  ['clipped scroller', '.mobile-page-body { height: 200px; overflow: hidden; }', /clips overflow/],
]) test(`rejects ${name}`, () => {
  const source = `${css}\n${bad}`;
  assert.ok(check(source).some(e => expected.test(e)));
  const dir = mkdtempSync(join(tmpdir(), 'float-lint-'));
  try {
    const file = join(dir, 'bad.css');
    writeFileSync(file, source);
    const result = spawnSync(process.execPath, ['scripts/check-floating-clearance.mjs', file], { encoding: 'utf8' });
    assert.equal(result.status, 1);
    assert.match(result.stderr, expected);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
test('rejects absent message clearance', () => {
  const altered = css.replace(/padding-bottom: var\(--mobile-dock-clearance\);/g, 'padding-bottom: 0;');
  assert.ok(check(altered).some(e => /message scroller/.test(e)));
});
