import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { walkRules, ruleViolations, scan, loadLedger, evaluate } from '../check-noclip-floating.mjs';

const rules = (css) => [...walkRules(css)].flatMap((r) => ruleViolations(r.selector, r.body));

// ── positive fixtures: every rule fires on its banned combination ─────────────
for (const [name, css, rule] of [
  ['ellipsis text', '.t { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }', 'text-truncation'],
  ['line clamp', '.t { -webkit-line-clamp: 3; overflow: hidden; }', 'text-truncation'],
  ['height cap with hidden', '.box { max-height: 200px; overflow: hidden; }', 'clip-no-scroll'],
  ['height cap without scroll', '.box { max-height: 40vh; }', 'cap-without-scroll'],
  ['positioned cap with hidden', '.pop { position: fixed; height: 300px; overflow: hidden; }', 'clip-no-scroll'],
  ['clip-path', '.card { clip-path: inset(0 0 20% 0); }', 'clip-path'],
  ['contain paint', '.body { contain: layout paint; }', 'contain-clip'],
  ['floating layer clipped', '.option-box { position: absolute; overflow: hidden; }', 'float-overflow-hidden'],
]) test(`flags ${name}`, () => assert.ok(rules(css).includes(rule), `expected ${rule} in ${JSON.stringify(rules(css))}`));

// ── negative fixtures: the compliant shape stays clean ────────────────────────
for (const [name, css] of [
  ['height cap with scroll axis', '.box { max-height: 200px; overflow-y: auto; }'],
  ['plain visual clip without text rules', '.deco { overflow: hidden; }'],
  ['wrapping text, no truncation', '.t { overflow-wrap: anywhere; white-space: normal; }'],
  ['scrolling floating layer', '.sheet { position: fixed; max-height: 60vh; overflow-y: auto; }'],
  ['non-floating bare height (icon)', '.icon { height: 24px; overflow: hidden; }'],
]) test(`allows ${name}`, () => assert.deepEqual(rules(css), []));

// ── walker: at-rules and keyframe stops are not element rules ─────────────────
test('walker skips at-rule preludes and keyframe stops', () => {
  const seen = [...walkRules('@media (max-width: 600px) { .a { color: red; } }\n@keyframes k { from { opacity: 0; } to { opacity: 1; } }')];
  assert.deepEqual(seen.map((r) => r.selector), ['.a']);
  assert.equal(seen[0].line, 1);
});

// ── evaluate: fresh vs ledgered vs improved ──────────────────────────────────
test('evaluate isolates unledgered violations and reports improvements', () => {
  const result = { entries: [{ key: 'f::text-truncation::.t', file: 'f', rule: 'text-truncation', selector: '.t', line: 1 }] };
  assert.equal(evaluate(result, { entries: [] }).fresh.length, 1);
  assert.equal(evaluate(result, { entries: result.entries }).fresh.length, 0);
  const gone = evaluate({ entries: [] }, { entries: result.entries });
  assert.deepEqual(gone.improved.map((e) => e.key), ['f::text-truncation::.t']);
});

// ── the repository's own tree: every current violation is ledgered ────────────
test('current web tree is fully covered by the named ledger', () => {
  const { fresh, total, ledgerSize } = evaluate(scan(), loadLedger());
  assert.deepEqual(fresh, []);
  assert.equal(total, ledgerSize);
});

// ── CLI behaviour: exit 1 on fresh, 0 when ledgered, 2 on SKIP ────────────────
// 2026-11-19 ci-static-reds 批：被测 CLI 的 ciMode 会读宿主的 CI / GITHUB_ACTIONS /
// GITLAB_CI（scripts/check-noclip-floating.mjs:141），在被 CI 注入的环境里把 SKIP 升级成
// FAIL(exit 1)，于是下面「无 web 树 ⇒ exit 2」那条在 GitHub Actions 上恒红（实测
// `Expected 2, actual 1`）。run() 显式删键（不是置空串）还原「非 CI」形态；要测升级
// 行为的那条用例自己显式传 `--ci`，不走 run()，不受影响。
const CLEAN_ENV = { ...process.env };
delete CLEAN_ENV.CI;
delete CLEAN_ENV.GITHUB_ACTIONS;
delete CLEAN_ENV.GITLAB_CI;
const run = (args) => spawnSync(process.execPath, ['scripts/check-noclip-floating.mjs', ...args], { encoding: 'utf8', env: CLEAN_ENV });

test('CLI is red on an unledgered violation and green once ledgered', () => {
  const dir = mkdtempSync(join(tmpdir(), 'noclip-'));
  try {
    writeFileSync(join(dir, 'bad.css'), '.new-card { max-height: 200px; overflow: hidden; }\n');
    writeFileSync(join(dir, 'empty.json'), JSON.stringify({ static: [] }));
    const red = run(['--web-root', dir, '--web-label', 'css', '--ledger', join(dir, 'empty.json')]);
    assert.equal(red.status, 1);
    assert.match(red.stderr, /new-card/);
    const emitted = run(['--web-root', dir, '--web-label', 'css', '--emit-ledger']);
    writeFileSync(join(dir, 'ledger.json'), JSON.stringify({ static: JSON.parse(emitted.stdout).entries }));
    const green = run(['--web-root', dir, '--web-label', 'css', '--ledger', join(dir, 'ledger.json')]);
    assert.equal(green.status, 0);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('CLI is red when the named ledger is missing', () => {
  const dir = mkdtempSync(join(tmpdir(), 'noclip-'));
  try {
    writeFileSync(join(dir, 'bad.css'), '.option-box { overflow: hidden; }\n');
    const result = run(['--web-root', dir, '--web-label', 'css', '--ledger', join(dir, 'absent.json')]);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /missing named ledger/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('CLI skips with exit 2 when the web tree is absent', () => {
  const result = run(['--web-root', join(tmpdir(), 'noclip-absent-tree'), '--no-ledger']);
  assert.equal(result.status, 2);
  assert.match(result.stderr, /SKIP/);
});

test('CLI escalates SKIP to failure under CI', () => {
  const result = spawnSync(process.execPath, ['scripts/check-noclip-floating.mjs', '--web-root', join(tmpdir(), 'noclip-absent-tree'), '--ci'], { encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /FAIL/);
});
