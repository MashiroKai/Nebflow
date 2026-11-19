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
  ['unregistered floating host', 'body.mobile-layer .new-floating-toolbar { position: fixed; bottom: 0; }', /unregistered fixed mobile host/],
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
test('rejects clearance assigned to wrong canvas object through CLI', () => {
  const source = css.replace(/(\.mf-canvas-page\s+)\.canvas-viewer-safe-top/g, '$1.unrelated-viewer');
  const dir = mkdtempSync(join(tmpdir(), 'float-mismatch-'));
  try {
    const file = join(dir, 'bad.css'); writeFileSync(file, source);
    const result = spawnSync(process.execPath, ['scripts/check-floating-clearance.mjs', file], { encoding: 'utf8' });
    assert.equal(result.status, 1); assert.match(result.stderr, /missing clearance for chrome: canvas tab strip/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
test('rejects absent message clearance', () => {
  const altered = css.replace(/([^{}]*#fm-conversations[^{}]*\{[^{}]*?)padding-bottom: var\(--mobile-scroll-end\)/, '$1padding-bottom: 0px');
  assert.ok(check(altered).some(e => /message scroller/.test(e)));
});
test('rejects broken single-source clearance chain', () => {
  const altered = css.replace(/--mobile-scroll-end: calc\(var\(--mobile-dock-clearance\)[^;]*;/, '--mobile-scroll-end: 72px;');
  assert.ok(check(altered).some(e => /no longer derives from/.test(e)), check(altered).join('\n'));
});
