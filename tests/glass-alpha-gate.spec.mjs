// Contract test for the alpha=1 gate; runs against disposable source copies, no server.
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const root = fileURLToPath(new URL('..', import.meta.url));
const tmp = mkdtempSync(join(tmpdir(), 'glass-alpha-gate-'));
try {
  cpSync(join(root, 'src/main/resources/web'), join(tmp, 'src/main/resources/web'), { recursive: true });
  mkdirSync(join(tmp, 'src/main/resources/seed/plugins/design-language/skills/design-language/references'), { recursive: true });
  cpSync(join(root, 'src/main/resources/seed/plugins/design-language/skills/design-language/references/glass-spec.md'),
    join(tmp, 'src/main/resources/seed/plugins/design-language/skills/design-language/references/glass-spec.md'), { recursive: true });
  const gate = join(root, 'scripts/check-glass-unify.mjs');
  const run = () => spawnSync(process.execPath, [gate, `--root=${tmp}`, '--json'], { encoding: 'utf8' });
  let result = run();
  assert.equal(result.status, 0, result.stderr || result.stdout);
  const tokens = join(tmp, 'src/main/resources/web/css/stand-tokens.css');
  const original = readFileSync(tokens, 'utf8');
  assert.ok(original.includes('--nf-glass-input-bg: rgba(128, 128, 128, 0.16);'));
  writeFileSync(tokens, original.replace('--nf-glass-input-bg: rgba(128, 128, 128, 0.16);', '--nf-glass-input-bg: #ffffffff;'));
  result = run();
  assert.equal(result.status, 1, result.stderr || result.stdout);
  assert.ok(JSON.parse(result.stdout).violations.some(v => v.rule === 'G3' && v.detail.includes('α=1.000')));
  console.log('PASS alpha=1 rejected; baseline accepted');
} finally {
  rmSync(tmp, { recursive: true, force: true });
}
