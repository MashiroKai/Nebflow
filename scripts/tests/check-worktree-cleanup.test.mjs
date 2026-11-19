// Regression cover for the 合并即清树 gate (author ruling 2026-11-19 20:31).
// The judgment is pure (`parseWorktrees` / `judge`); the two exit-code paths are pinned
// end-to-end on throwaway repositories so "merged but hanging" and "nothing to judge"
// can never be confused for one another.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { judge, parseWorktrees } from '../check-worktree-cleanup.mjs';

const GATE = fileURLToPath(new URL('../check-worktree-cleanup.mjs', import.meta.url));

function git(cwd, args) {
  const result = spawnSync('git', ['-c', 'user.name=gate', '-c', 'user.email=gate@example.invalid',
    '-c', 'commit.gpgsign=false', '-c', 'init.defaultBranch=main', ...args], { cwd, encoding: 'utf8' });
  if (result.status !== 0) throw new Error(`git ${args.join(' ')} failed: ${(result.stderr ?? '').trim() || result.status}`);
  return (result.stdout ?? '').trim();
}

function gate(root) {
  const result = spawnSync(process.execPath, [GATE, '--root', root], { encoding: 'utf8', timeout: 60_000 });
  return { code: result.status, output: `${result.stdout ?? ''}${result.stderr ?? ''}` };
}

// A throwaway repo shaped like the unified layout: linked trees under `.stand/worktrees/<name>`.
function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'wt-cleanup-gate-'));
  git(root, ['init', '-q']);
  git(root, ['commit', '-q', '--allow-empty', '-m', 'seed']);
  mkdirSync(join(root, '.stand', 'worktrees'), { recursive: true });
  return {
    root,
    tree: name => join(root, '.stand', 'worktrees', name),
    addWorktree: (name, branch, commit) => {
      git(root, ['worktree', 'add', '-q', '-b', branch, join(root, '.stand', 'worktrees', name)]);
      if (commit) git(join(root, '.stand', 'worktrees', name), ['commit', '-q', '--allow-empty', '-m', commit]);
    },
    dispose: () => rmSync(root, { recursive: true, force: true }),
  };
}

test('parseWorktrees reads porcelain records including detached, bare and prunable', () => {
  const records = parseWorktrees([
    'worktree /repo', 'HEAD 1111111111111111111111111111111111111111', 'branch refs/heads/feat/base', '',
    'worktree /repo/.stand/worktrees/a b', 'HEAD 2222222222222222222222222222222222222222', 'detached', '',
    'worktree /repo/.stand/worktrees/gone', 'HEAD 3333333333333333333333333333333333333333',
    'branch refs/heads/feat/gone', 'prunable gitdir file points to non-existent location', '',
    'worktree /repo/bare.git', 'bare', '', ''].join('\n'));
  assert.equal(records.length, 4);
  assert.equal(records[0].branch, 'feat/base');
  assert.equal(records[1].path, '/repo/.stand/worktrees/a b');
  assert.equal(records[1].detached, true);
  assert.equal(records[1].branch, null);
  assert.equal(records[2].prunable, 'gitdir file points to non-existent location');
  assert.equal(records[3].bare, true);
});

test('judge flags a merged branch that still holds a worktree', () => {
  const records = parseWorktrees([
    'worktree /repo', 'HEAD aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'branch refs/heads/feat/base', '',
    'worktree /repo/.stand/worktrees/landed', 'HEAD bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 'branch refs/heads/feat/landed', ''].join('\n'));
  const result = judge(records, [{ name: 'feat/base', sha: 'a'.repeat(40) }], () => true);
  assert.equal(result.violations.length, 1);
  assert.equal(result.violations[0].branch, 'feat/landed');
  assert.match(result.violations[0].criterion, /is an ancestor of feat\/base@aaaaaaaa/);
  assert.equal(result.judged, 0);
});

test('judge leaves unmerged worktrees alone and counts them as judged', () => {
  const records = parseWorktrees([
    'worktree /repo', 'HEAD aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'branch refs/heads/feat/base', '',
    'worktree /repo/.stand/worktrees/wip', 'HEAD bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 'branch refs/heads/feat/wip', ''].join('\n'));
  const result = judge(records, [{ name: 'feat/base', sha: 'a'.repeat(40) }], () => false);
  assert.deepEqual(result.violations, []);
  assert.equal(result.judged, 1);
});

test('judge never turns "nothing to judge" into a violation, nor the reverse', () => {
  const onlyPrimary = parseWorktrees('worktree /repo\nHEAD aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\nbranch refs/heads/feat/base\n');
  const empty = judge(onlyPrimary, [{ name: 'feat/base', sha: 'a'.repeat(40) }], () => true);
  assert.deepEqual(empty.violations, []);
  assert.equal(empty.judged, 0);

  const records = parseWorktrees([
    'worktree /repo', 'HEAD aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'branch refs/heads/feat/base', '',
    'worktree /repo/.stand/worktrees/wip', 'HEAD bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 'branch refs/heads/feat/wip', ''].join('\n'));
  // Both integration refs unavailable: the gate must skip, not guess.
  const unjudgeable = judge(records, [], () => true);
  assert.deepEqual(unjudgeable.violations, []);
  assert.equal(unjudgeable.skipped.length, 1);
  // A merge-base lookup that cannot answer is a skip, never a violation.
  const unknown = judge(records, [{ name: 'main', sha: 'c'.repeat(40) }], () => undefined);
  assert.deepEqual(unknown.violations, []);
  assert.equal(unknown.skipped.length, 1);
});

test('judge keeps a pruned admin record out of the violations', () => {
  const records = parseWorktrees([
    'worktree /repo', 'HEAD aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'branch refs/heads/feat/base', '',
    'worktree /repo/.stand/worktrees/gone', 'HEAD bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
    'branch refs/heads/feat/gone', 'prunable gitdir file points to non-existent location', ''].join('\n'));
  const result = judge(records, [{ name: 'feat/base', sha: 'a'.repeat(40) }], () => true, { exists: () => false });
  assert.deepEqual(result.violations, []);
  assert.equal(result.prunable.length, 1);
});

test('gate exits 0 while the branch is unmerged, 1 once it is merged, 0 again after reclaim', () => {
  const repo = fixture();
  try {
    repo.addWorktree('wip', 'feat/wip', 'work in progress');
    const clean = gate(repo.root);
    assert.equal(clean.code, 0, clean.output);
    assert.match(clean.output, /PASS worktree-cleanup: 1 judged, 0 merged-but-hanging/);
    assert.match(clean.output, /exit 0 — judgeable objects present and clean/);

    repo.addWorktree('landed', 'feat/landed');
    git(repo.root, ['merge', '-q', '--ff-only', 'feat/landed']);
    const red = gate(repo.root);
    assert.equal(red.code, 1, red.output);
    assert.match(red.output, /VIOLATION merged-but-hanging path=.*\/landed branch=feat\/landed criterion=[0-9a-f]{8} is an ancestor of main@[0-9a-f]{8}/);
    assert.match(red.output, /exit 1 — local check with judgeable objects/);

    git(repo.root, ['worktree', 'remove', repo.tree('landed')]);
    const green = gate(repo.root);
    assert.equal(green.code, 0, green.output);
    assert.match(green.output, /PASS worktree-cleanup/);
  } finally { repo.dispose(); }
});

test('gate exits 0 with an explicit nothing-to-judge note on a checkout with no linked worktree', () => {
  const repo = fixture();
  try {
    const result = gate(repo.root);
    assert.equal(result.code, 0, result.output);
    assert.match(result.output, /NOTE: no judgeable linked worktree/);
    assert.match(result.output, /nothing to judge, so NOT a residual and NOT a pass claim about cleanliness/);
    assert.match(result.output, /exit 0 — no object to judge/);
  } finally { repo.dispose(); }
});

// 现金（landing）驱动同受这条规则约束：只 `worktree remove`（无 --force）、三前置条件齐、
// 分支一律保留。merge-sink.py 的清理段是流程内联代码，用源码契约钉住它的形状。
test('merge-sink reclaims the tree without --force, checks three preconditions and keeps the branch', () => {
  const source = readFileSync(fileURLToPath(new URL('../../scripts/merge-sink.py', import.meta.url)), 'utf8');
  assert.match(source, /\["git", "worktree", "remove", WT\]/, 'reclamation action is git worktree remove');
  assert.doesNotMatch(source, /"worktree",\s*"remove"[^\n]*"--force"/, 'no --force');
  assert.doesNotMatch(source, /^\s*run\(\["git", "branch", "-d"/m, 'cleanup never deletes the branch');
  assert.match(source, /git", "status", "--porcelain"/, 'precondition: clean tree');
  assert.match(source, /\["lsof", "\+D", WT\]/, 'precondition: no occupying process');
  assert.match(source, /MERGE_HEAD", "CHERRY_PICK_HEAD", "rebase-merge", "rebase-apply"/, 'precondition: no in-flight operation');
  assert.match(source, /SKIP-RECLAIM:/, 'a skipped tree is named, not force-deleted');
  assert.match(source, /RECLAIM: 已删 \{WT\}（约 \{size_kb\} KiB）/, 'the landing report records what was reclaimed');
});
