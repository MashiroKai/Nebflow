// G7 must not mistake a no-backdrop override for the normal surface.
// Mutations happen only in disposable copies; the source tree is unchanged.
import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolveDesignSpec, specMissingMessage } from '../scripts/lib/design-spec-source.mjs';
const root = fileURLToPath(new URL('..', import.meta.url));
// 规范单源缺失（本仓 design-language/ 被删或缺件）时本契约测试跑不了 —— 明确 SKIP（不假绿成 PASS）。
const specFile = resolveDesignSpec('references/frontend/glass-spec.md');
if (!specFile) {
  console.log(`SKIP glass-fallback-registry — ${specMissingMessage('references/frontend/glass-spec.md')}`);
  process.exit(0);
}
const tmp = mkdtempSync(join(tmpdir(), 'glass-fallback-'));
try {
  cpSync(join(root, 'src/main/resources/web'), join(tmp, 'src/main/resources/web'), { recursive: true });
  // 规范面拷进一次性包根（--spec-root=<dir>），与判定面（--root=<dir>）分离；
  // 本测试要**改规范**（登记表/待解析名单）⇒ 改的是一次性包根里那份，源树零改动。
  const specRoot = join(tmp, 'specroot');
  const specdir = join(specRoot, 'references', 'frontend');
  mkdirSync(specdir, { recursive: true });
  cpSync(specFile, join(specdir, 'glass-spec.md'));
  const path = join(tmp, 'src/main/resources/web/css/glass.css');
  const original = readFileSync(path, 'utf8');
  const run = () => {
    const result = spawnSync(process.execPath, [join(root, 'scripts/check-glass-unify.mjs'), `--root=${tmp}`, `--spec-root=${specRoot}`, '--json'], { encoding: 'utf8' });
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
  const spec = join(specdir, 'glass-spec.md');
  const specOriginal = readFileSync(spec, 'utf8');
  writeFileSync(spec, specOriginal.replace('| `#header` | 复合', '| `.mh-search-bar` | 不可解析 | 人工伪造豁免 |\n| `#header` | 复合'));
  result = run();
  assert.equal(result.code, 1, 'new pending exemption must fail');
  assert.ok(result.json.violations.some(v => v.rule === 'G7b' && v.selector === '.mh-search-bar' && v.detail.includes('待解析名单')), JSON.stringify(result.json.violations));
  console.log('PASS adding an eighth pending selector rejected');
} finally { rmSync(tmp, { recursive: true, force: true }); }
