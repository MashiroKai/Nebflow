#!/usr/bin/env node
// 合并即清树兜底闸（作者裁定 2026-11-19 20:31「落实到我们合并的系统规则 合并就清树」）：
// 一支并入整合线（main / feat/base）之后，该支不许再挂着一棵工作树。本闸只判「并入」与
// 「工作树仍在」两个事实，不做删除动作，也不判工作树脏不脏 —— 回收动作与其三个前置条件
// （干净 / 无进程占用 / 无 MERGE_HEAD·rebase-*·CHERRY_PICK_HEAD）见 CONTRIBUTING.md
// 「Landing rules」与 branch-governance BG-REQ-003。
//
// 退出码策略（两种情形都必须在输出里写明，且**不许互相冒充**）：
//   exit 1 = 本机/开发检出：存在可判对象（链接工作树），且其中有「已并入却仍挂着」的 ⇒ 红。
//   exit 0 = 两种情形之一：(a) 可判对象都存在但都未并入 ⇒ 绿（真绿）；
//            (b) 没有可判对象（CI/托管主检出只有主 checkout，或整合线 ref 取不到）⇒ 绿，
//            但输出明写「无对象可判」——「无对象可判」绝不冒充「无残留」，
//            「有残留」也绝不冒充「无对象」。
//            CI 主检出/托管检出没有链接工作树是常态，红在这里会变成噪声红，故取 (b) 绿。
//   exit 2 = 闸自身跑不动（git 不可用 / git worktree list 失败）—— 不是对残留的判定。
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
// 整合线：main（晋级线）与 feat/base（在飞整合线）。取不到的那条不判，不猜。
export const INTEGRATION_REFS = ['main', 'feat/base'];
const DEFAULT_MAX_VIOLATIONS = 60;

/** Parse `git worktree list --porcelain` into records; the first record is the primary checkout. */
export function parseWorktrees(porcelain) {
  const records = [];
  let current;
  const push = () => { if (current) records.push(current); current = undefined; };
  for (const line of porcelain.split('\n')) {
    if (!line.trim()) { push(); continue; }
    const space = line.indexOf(' ');
    const key = space === -1 ? line : line.slice(0, space);
    const value = space === -1 ? '' : line.slice(space + 1);
    if (key === 'worktree') {
      push();
      current = { path: value, head: null, branch: null, bare: false, detached: false, prunable: null };
      continue;
    }
    if (!current) throw new Error(`Unexpected worktree record line: ${line}`);
    if (key === 'HEAD') current.head = value;
    else if (key === 'branch') current.branch = value.replace(/^refs\/heads\//, '');
    else if (key === 'bare') current.bare = true;
    else if (key === 'detached') current.detached = true;
    else if (key === 'prunable') current.prunable = value || 'prunable';
    // Unknown keys (e.g. `locked`) are ignored on purpose: they never make a tree judgeable.
  }
  push();
  return records;
}

export function label(record) {
  const branch = record.branch ?? (record.detached ? '(detached)' : '(no branch)');
  return { path: record.path, branch, head: record.head ?? '?' };
}

/**
 * Judge every linked worktree against the integration refs.
 * `isAncestor(head, refSha)` returns true / false / undefined (= cannot judge -> skip, never a violation).
 * Pure: no git, no fs — the CLI supplies both.
 */
export function judge(records, refs, isAncestor, { exists = () => true } = {}) {
  const violations = [];
  const skipped = [];
  const prunable = [];
  let judged = 0;
  for (const record of records.slice(1)) {
    const id = label(record);
    if (record.bare) { skipped.push({ ...id, reason: 'bare repository record' }); continue; }
    if (!record.head) { skipped.push({ ...id, reason: 'no HEAD recorded' }); continue; }
    if (!refs.length) { skipped.push({ ...id, reason: 'integration ref unavailable (main / feat/base)' }); continue; }
    if (record.prunable || !exists(record.path)) {
      // 目录已经不在 = 只剩 .git/worktrees 里的管理记录，不是「仍挂着的工作树」。
      prunable.push({ ...id, reason: record.prunable || 'path missing' });
      continue;
    }
    const verdicts = refs.map(ref => [ref, isAncestor(record.head, ref.sha)]);
    const unknown = verdicts.filter(([, verdict]) => verdict === undefined).map(([ref]) => ref.name);
    const contains = verdicts.filter(([, verdict]) => verdict === true).map(([ref]) => ref.name);
    if (contains.length) {
      // 已并入的判据逐条写进报告：哪条整合线、哪个 tip、本树 HEAD 是否为它的祖先。
      violations.push({ ...id, refs: contains, criterion: `${record.head.slice(0, 8)} is an ancestor of ${contains.map(name => `${name}@${refs.find(ref => ref.name === name).sha.slice(0, 8)}`).join(', ')}` });
      continue;
    }
    if (unknown.length) { skipped.push({ ...id, reason: `merge-base unavailable for ${unknown.join(', ')}` }); continue; }
    judged++;
  }
  return { primary: records[0] ? label(records[0]) : null, judged, violations, skipped, prunable };
}

function git(root, args) {
  return spawnSync('git', args, { cwd: root, encoding: 'utf8', timeout: 60_000 });
}

function integrationRefs(root) {
  const refs = [];
  const missing = [];
  for (const name of INTEGRATION_REFS) {
    const result = git(root, ['rev-parse', '--verify', '--quiet', `refs/heads/${name}^{commit}`]);
    const sha = (result.stdout ?? '').trim();
    if (result.status === 0 && /^[0-9a-f]{40,64}$/.test(sha)) refs.push({ name, sha });
    else missing.push(name);
  }
  return { refs, missing };
}

export function run({ root = ROOT, max = DEFAULT_MAX_VIOLATIONS, log = console.log } = {}) {
  const listed = git(root, ['worktree', 'list', '--porcelain']);
  if (listed.error) throw listed.error;
  if (listed.status !== 0) throw new Error(`git worktree list failed in ${root}: ${(listed.stderr ?? '').trim() || `exit ${listed.status}`}`);
  const records = parseWorktrees(listed.stdout ?? '');
  const { refs, missing } = integrationRefs(root);
  // 每棵树的「已并入」判据 = 本树 HEAD 是不是该整合线 tip 的祖先。逐棵 merge-base 是 2×N 次
  // 子进程（本机 181 棵 ≈ 12s）；改为每条整合线取一次 rev-list 祖先集，判据不变、开销降到 2 次。
  const ancestors = new Map();
  for (const ref of refs) {
    const result = git(root, ['rev-list', ref.sha]);
    if (result.status === 0) ancestors.set(ref.sha, new Set((result.stdout ?? '').split('\n').filter(Boolean)));
  }
  const isAncestor = (head, integrationSha) => {
    const set = ancestors.get(integrationSha);
    return set ? set.has(head) : undefined;
  };
  const result = judge(records, refs, isAncestor, { exists: existsSync });
  const linked = Math.max(0, records.length - 1);

  log(`worktree-cleanup gate: root=${root} linkedWorktrees=${linked} integrationRefs=${refs.map(ref => `${ref.name}@${ref.sha.slice(0, 8)}`).join(' ') || '(none)'}${missing.length ? ` unavailable=${missing.join(',')}` : ''}`);
  if (result.judged + result.violations.length === 0) {
    log(`NOTE: no judgeable linked worktree — ${linked === 0 ? 'this checkout has no linked worktree (CI / hosted primary checkout)' : 'every linked worktree was skipped'}; nothing to judge, so NOT a residual and NOT a pass claim about cleanliness.`);
  }
  for (const item of result.prunable) log(`STALE-ADMIN (not a hanging worktree) ${item.path} branch=${item.branch} reason=${item.reason}`);
  for (const item of result.skipped) log(`SKIP (not judged) ${item.path} branch=${item.branch} reason=${item.reason}`);
  const shown = result.violations.slice(0, max);
  for (const item of shown) log(`VIOLATION merged-but-hanging path=${item.path} branch=${item.branch} criterion=${item.criterion}`);
  if (result.violations.length > shown.length) log(`... ${result.violations.length - shown.length} more violation(s) not shown (raise --max)`);
  if (result.violations.length) {
    log(`FAIL worktree-cleanup: ${result.violations.length} merged branch(es) still hold a worktree out of ${result.judged + result.violations.length} judged. Reclaim each with \`git worktree remove <path>\` (no --force) after the three preconditions; keep the branch.`);
    log('exit 1 — local check with judgeable objects: merged branch still has a worktree.');
    return 1;
  }
  log(`PASS worktree-cleanup: ${result.judged} judged, 0 merged-but-hanging.`);
  log(`exit 0 — ${result.judged ? 'judgeable objects present and clean.' : 'no object to judge (CI / hosted primary checkout or refs unavailable); this is NOT a claim that local trees were reclaimed.'}`);
  return 0;
}

function main(args) {
  let root = ROOT;
  let max = DEFAULT_MAX_VIOLATIONS;
  for (let index = 0; index < args.length; index++) {
    const arg = args[index];
    if (arg === '--root') {
      const value = args[++index];
      if (!value || value.startsWith('--')) throw new Error('Missing value for --root');
      root = resolve(value);
    } else if (arg === '--max') {
      const value = args[++index];
      if (!value || !/^\d+$/.test(value)) throw new Error('Missing or invalid value for --max');
      max = Number(value);
    } else throw new Error(`Unknown argument: ${arg}`);
  }
  return run({ root, max });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = main(process.argv.slice(2));
  } catch (error) {
    console.error(`worktree-cleanup ERROR: ${error.message}`);
    process.exitCode = 2;
  }
}
