// Contract test for the alpha=1 gate; runs against disposable source copies, no server.
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { resolveDesignSpec, specMissingMessage } from '../scripts/lib/design-spec-source.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
// 规范单源（stand-dev 插件包）缺席时本契约测试跑不了 —— 明确 SKIP（不假绿成 PASS）。
const specFile = resolveDesignSpec('references/frontend/glass-spec.md');
if (!specFile) {
  console.log(`SKIP glass-alpha-gate — ${specMissingMessage('references/frontend/glass-spec.md')}`);
  process.exit(0);
}
const tmp = mkdtempSync(join(tmpdir(), 'glass-alpha-gate-'));
try {
  cpSync(join(root, 'src/main/resources/web'), join(tmp, 'src/main/resources/web'), { recursive: true });
  // 规范面拷进一次性包根（--spec-root=<dir>），与判定面（--root=<dir>）分离。
  const specRoot = join(tmp, 'specroot');
  mkdirSync(join(specRoot, 'references', 'frontend'), { recursive: true });
  cpSync(specFile, join(specRoot, 'references', 'frontend', 'glass-spec.md'));
  const gate = join(root, 'scripts/check-glass-unify.mjs');
  const run = () => spawnSync(process.execPath, [gate, `--root=${tmp}`, `--spec-root=${specRoot}`, '--json'], { encoding: 'utf8' });
  let result = run();
  assert.equal(result.status, 0, result.stderr || result.stdout);
  const tokens = join(tmp, 'src/main/resources/web/css/stand-tokens.css');
  const original = readFileSync(tokens, 'utf8');
  // 亮档唯一玻璃底（值随批前进；断言「改动前基线在场、改后 α=1 被拒」这一对）
  assert.ok(original.includes('--nf-glass-input-bg: rgba(255, 255, 255, 0.16);'));
  writeFileSync(tokens, original.replace('--nf-glass-input-bg: rgba(255, 255, 255, 0.16);', '--nf-glass-input-bg: #ffffffff;'));
  result = run();
  assert.equal(result.status, 1, result.stderr || result.stdout);
  assert.ok(JSON.parse(result.stdout).violations.some(v => v.rule === 'G3' && v.detail.includes('α=1.000')));
  console.log('PASS alpha=1 rejected; baseline accepted');
} finally {
  rmSync(tmp, { recursive: true, force: true });
}
