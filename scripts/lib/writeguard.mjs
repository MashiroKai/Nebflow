// scripts/lib/writeguard.mjs — 写根守卫（W1，2026-09-15 e2efix 批）
//
// 与 `delguard.mjs`（递归**删除**守卫）同族、互补：delguard 管 delete，本件管 **write**。
// 事故动因：`scripts/e2e-askuser-source-label.mjs` 的截图输出目录**硬编码** `~/.nebflow/docs/Nebflow/
// assets/20260908_f1f2-askuser`，一次实跑覆写 4 个 tracked 人类资产（2026-09-15 晨，已恢复）。
// ⇒ 任何 e2e / 夹具脚本在**产生任何写入之前**先对本脚本的**全部写入路径**做断言，越界 = fail-fast 非零退出。
//
// 口径
//   1. `assertWritePath()` / `guardWrites()`：**解析后的真实绝对路径**必须**strictly under** 某个允许根
//      （根自身不算：根是目录，不是写入目标），且不得命中拒绝表。
//   2. 允许根（默认三处，与 e2efix 批任务书 ③(a) 逐字对应）：
//        (a) `kind=repo`     —— 本支 checkout 根之下（worktree 或主区，调用方传 repoRoot）；
//        (b) `kind=evidence` —— `<主区 checkout>/.nebflow/evidence/<NB_EVIDENCE_BATCH|20260915_e2eask>/` 之下
//            （主区 = repoRoot 形如 `<X>/.nebflow/worktrees/<name>` 时上溯两级；`NB_MAIN_REPO` 可显式覆盖）；
//        (c) `kind=tmp`      —— `realpath('/tmp')` 与 `realpath(os.tmpdir())` 之下的**一级 `qa-*` 目录**之下
//            （`/tmp/qa-foo/x.png` ✓；`/tmp/other/x.png` ✗；`/tmp/qa-foo` 自身 ✗ —— 防 `rm -rf /tmp/qa-*` 类误伤面）。
//   3. 拒绝表（**先于**允许根判定，优先级最高）：宿主数据根 `<homedir>/.nebflow/**` —— 任何写入一律拒
//      （红线：禁写宿主 `~/.nebflow`、禁写 `~/.nebflow/docs/**`；**只读读数不受本守卫约束**，本件只管写）。
//   4. 逃逸处置与 delguard 同源（复用其 `resolveReal`）：词法归一（`.`/`..`/重复斜杠/相对路径按 cwd）→
//      最深已存在祖先 realpath（macOS `/tmp` symlink、symlink 指向 `~/.nebflow` 一类逃逸会被揭穿）→ 比较。
//   5. `NB_WRITE_ROOTS=<abs:abs:...>` 显式覆盖允许根集（仍受第 3 条拒绝表约束）——负控 / 上游复用入口。
//
// CLI（负控入口，只断言、不写入任何文件）：
//   node scripts/lib/writeguard.mjs <path> [...]          # rc=0 全通过 / rc=3 有拒绝
//   node scripts/lib/writeguard.mjs --print-roots         # 打印生效允许根 + 拒绝表
//
// 负控（无需启动任何服务）：
//   node scripts/lib/writeguard.mjs ~/.nebflow/docs/Nebflow/assets/x.png   # 期望 rc=3（拒绝表）
//   node scripts/lib/writeguard.mjs /tmp/qa-probe/x.png                    # 期望 rc=0
//   node scripts/lib/writeguard.mjs "$(pwd)/scripts/lib/writeguard.mjs"    # 期望 rc=0（本支 repo 内）

import { existsSync, realpathSync } from 'node:fs';
import { dirname, join, sep } from 'node:path';
import { homedir, tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { resolveReal } from './delguard.mjs';

/** 写根越界（携带解析后绝对路径与拒绝原因，便于负控断言输出） */
export class WriteRefused extends Error {
  constructor(message, { resolved = null, raw = null, reason = null } = {}) {
    super(message);
    this.name = 'WriteRefused';
    this.resolved = resolved;
    this.raw = raw;
    this.reason = reason;
  }
}

const DEFAULT_EVIDENCE_BATCH = '20260915_e2eask';

function realpathOrNull(p) {
  try { return realpathSync(p); } catch { return null; }
}

/** 主区 checkout：`<X>/.nebflow/worktrees/<name>` → `<X>`；其余原样（worktree 形态不变即主区）。 */
export function mainRepoOf(repoRoot) {
  const m = /^(.*)\/.nebflow\/worktrees\/[^/]+$/.exec(repoRoot);
  return m ? m[1] : repoRoot;
}

export function resolveDeniedRoots({ env = process.env } = {}) {
  const out = [];
  const host = realpathOrNull(homedir());
  if (host) out.push({ kind: 'host-data-root', path: join(host, '.nebflow') });
  return out;
}

/**
 * 生效允许根。返回 [{kind, path}]；path 均已归一（realpath 已存在前缀）。
 * 失败（无法解析）即抛 —— 绝不静默放行。
 */
export function defaultWriteRoots({ repoRoot = process.cwd(), env = process.env } = {}) {
  if (env.NB_WRITE_ROOTS) {
    const list = env.NB_WRITE_ROOTS.split(':').map((s) => s.trim()).filter(Boolean);
    if (!list.length) throw new WriteRefused('NB_WRITE_ROOTS 为空串 —— 拒绝（fail-closed）', { raw: env.NB_WRITE_ROOTS });
    return list.map((p) => ({ kind: 'explicit', path: resolveReal(p) }));
  }
  const repo = resolveReal(repoRoot);
  const main = resolveReal(env.NB_MAIN_REPO || mainRepoOf(repo));
  const roots = [
    { kind: 'repo', path: repo },
    { kind: 'evidence', path: join(main, '.nebflow', 'evidence', env.NB_EVIDENCE_BATCH || DEFAULT_EVIDENCE_BATCH) },
  ];
  for (const t of ['/tmp', tmpdir()]) {
    const r = realpathOrNull(t);
    if (r && !roots.some((x) => x.path === r)) roots.push({ kind: 'tmp', path: r });
  }
  return roots;
}

function under(resolved, root) {
  return resolved.startsWith(root.endsWith(sep) ? root : root + sep);
}

/** tmp 根额外约束：一级目录须为 `qa-*`（任务书 ③(a) 的 `/tmp/qa-*` 逐字口径）。 */
function tmpFirstSegmentOk(resolved, root) {
  const rest = resolved.slice(root.length + 1);
  const first = rest.split(sep)[0];
  return first.startsWith('qa-');
}

/**
 * 断言单个**写入路径**。通过 → 返回解析后绝对路径；否则抛 WriteRefused。
 */
export function assertWritePath(target, { repoRoot = process.cwd(), env = process.env, label = 'writeguard', roots = null } = {}) {
  const resolved = resolveReal(target);
  for (const d of resolveDeniedRoots({ env })) {
    if (resolved === d.path || under(resolved, d.path)) {
      throw new WriteRefused(
        `[${label}] 命中拒绝表（宿主数据根 ${d.kind}）→ 拒绝写入 resolved=${resolved}`,
        { raw: target, resolved, reason: 'denied-root' });
    }
  }
  const allow = roots ?? defaultWriteRoots({ repoRoot, env });
  for (const r of allow) {
    if (resolved === r.path) {
      throw new WriteRefused(
        `[${label}] 目标是允许根自身（不是写入目标）→ 拒绝 resolved=${resolved} root=${r.path}（kind=${r.kind}）`,
        { raw: target, resolved, reason: 'root-itself' });
    }
    if (under(resolved, r.path)) {
      if (r.kind === 'tmp' && !tmpFirstSegmentOk(resolved, r.path)) {
        throw new WriteRefused(
          `[${label}] tmp 根之下一级目录须为 qa-* → 拒绝 resolved=${resolved} root=${r.path}`,
          { raw: target, resolved, reason: 'tmp-prefix' });
      }
      return resolved;
    }
  }
  throw new WriteRefused(
    `[${label}] 不在任何允许根之下 → 拒绝写入 resolved=${resolved} roots=[${allow.map((r) => `${r.kind}:${r.path}`).join(' ')}]`,
    { raw: target, resolved, reason: 'outside-roots' });
}

/**
 * 早期 fail-fast 入口：逐个断言，任一越界 → stderr 打印全部违规 → `exit 3`（非零），**绝不降级**。
 * 通过 → 返回解析后绝对路径数组（调用方可直接用它们替代原始值）。
 */
export function guardWrites(paths, { repoRoot = process.cwd(), env = process.env, label = 'writeguard', roots = null, exit = true } = {}) {
  const allow = roots ?? defaultWriteRoots({ repoRoot, env });
  const ok = [];
  const bad = [];
  for (const p of paths) {
    try {
      ok.push(assertWritePath(p, { repoRoot, env, label, roots: allow }));
    } catch (e) {
      bad.push(e);
    }
  }
  if (bad.length) {
    for (const e of bad) {
      console.error(`[writeguard] REFUSED（${label}）：${e.message}`);
      console.error(`[writeguard] REFUSED（${label}）：raw=${String(e.raw)} resolved=${e.resolved ?? '<未解析>'} reason=${e.reason ?? '<unknown>'}`);
    }
    console.error(`[writeguard] 允许根：${allow.map((r) => `${r.kind}=${r.path}`).join('  ')}`);
    console.error('[writeguard] 拒绝表：' + resolveDeniedRoots({ env }).map((d) => `${d.kind}=${d.path}`).join('  '));
    if (exit) process.exit(3);
  }
  console.log(`[writeguard] 写根断言通过（${label}）：${ok.length} 路径 —— ${ok.map((p) => p).join(' | ')}`);
  return ok;
}

// ── CLI（只断言，不写入）：node scripts/lib/writeguard.mjs [--print-roots] <路径>... ──
const SELF_REAL = realpathOrNull(fileURLToPath(import.meta.url)) ?? fileURLToPath(import.meta.url);
const ARGV_REAL = process.argv[1] ? (realpathOrNull(process.argv[1]) ?? process.argv[1]) : null;
if (ARGV_REAL && ARGV_REAL === SELF_REAL) {
  const args = process.argv.slice(2);
  // SELF 在 <repo>/scripts/lib/ 下 ⇒ 上溯三级为 repo 根
  const repoRoot = process.env.NB_REPO_ROOT || dirname(dirname(dirname(SELF_REAL)));
  if (args.includes('--print-roots')) {
    console.log('repoRoot=' + resolveReal(repoRoot));
    for (const r of defaultWriteRoots({ repoRoot })) console.log(`ALLOW  ${r.kind}  ${r.path}`);
    for (const d of resolveDeniedRoots({})) console.log(`DENY   ${d.kind}  ${d.path}`);
    process.exit(0);
  }
  if (!args.length) {
    console.error('用法: node scripts/lib/writeguard.mjs <路径> [...]   （只跑断言，不写入；rc=0 通过 / rc=3 拒绝）');
    console.error('      node scripts/lib/writeguard.mjs --print-roots');
    process.exit(64);
  }
  let refused = 0;
  for (const t of args) {
    try {
      const r = assertWritePath(t, { repoRoot, label: 'cli' });
      console.log(`OK       raw=${t} resolved=${r}`);
    } catch (e) {
      refused++;
      console.error(`REFUSED  raw=${t} resolved=${e.resolved ?? '<未解析>'} reason=${e.reason ?? '<unknown>'} :: ${e.message}`);
    }
  }
  process.exit(refused ? 3 : 0);
}

export default { assertWritePath, guardWrites, defaultWriteRoots, resolveDeniedRoots, mainRepoOf, WriteRefused };
