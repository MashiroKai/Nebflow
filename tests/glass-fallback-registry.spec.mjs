// G7 must not mistake a no-backdrop override for the normal surface.
// Mutations happen only in disposable copies; the source tree is unchanged.
import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('..', import.meta.url));
const tmp = mkdtempSync(join(tmpdir(), 'glass-fallback-'));
try {
  cpSync(join(root, 'src/main/resources/web'), join(tmp, 'src/main/resources/web'), { recursive: true });
  const specdir = 'src/main/resources/seed/plugins/design-language/skills/design-language/references';
  mkdirSync(join(tmp, specdir), { recursive: true });
  cpSync(join(root, specdir, 'glass-spec.md'), join(tmp, specdir, 'glass-spec.md'));
  const path = join(tmp, 'src/main/resources/web/css/glass.css');
  const original = readFileSync(path, 'utf8');
  const run = () => {
    const result = spawnSync(process.execPath, [join(root, 'scripts/check-glass-unify.mjs'), `--root=${tmp}`, '--json'], { encoding: 'utf8' });
    assert.ok(result.stdout, result.stderr);
    return { code: result.status, json: JSON.parse(result.stdout) };
  };
  let result = run();
  assert.equal(result.code, 0, JSON.stringify(result.json.violations));
  assert.ok(result.json.topOverlays.degradeRuleCount > 0);
  const mutant = '\nbody.mobile-layer .mh-search-bar { background: var(--color-surface); }\n';
  writeFileSync(path, original + mutant);
  result = run();
  assert.equal(result.code, 1, 'normal solid background must fail');
  assert.ok(result.json.violations.some(v => v.rule === 'G7b' && v.selector === '.mh-search-bar'), JSON.stringify(result.json.violations));
  console.log('PASS fallback separated; normal solid background rejected by G7b');
  writeFileSync(path, original + '\nbody.mobile-layer .mh-search-bar { background: var(--unknown-glass-token); }\n');
  result = run();
  assert.equal(result.code, 1, 'unknown unlisted background must fail');
  assert.ok(result.json.violations.some(v => v.rule === 'G7b' && v.selector === '.mh-search-bar' && v.detail.includes('无法解析')), JSON.stringify(result.json.violations));
  console.log('PASS unlisted unresolved background rejected by G7b');
} finally { rmSync(tmp, { recursive: true, force: true }); }
