// scripts/lib/delguard.mjs — 递归删除守卫（R4，2026-09-11 delguard 批）
//
// 口径（六个 e2e/smoke 脚本共用；等价内联实现须与本文件一致）：
//   1. 任何递归删除前先硬断言：**解析后的真实绝对路径**必须落在允许根之下（默认 realpath(os.tmpdir())），
//      且 ≠ 允许根本身；不满足即 abort + 打印解析后绝对路径 + 非零退出。
//   2. 断言在启动早期先跑一次（fail-fast，任何 spawn/网络动作之前），删除点再跑一次。
//   3. 默认**不删**：要删须显式 CLEAN=1 / NB_CLEAN=1；KEEP=1 仍表示保留（兼容，无副作用）。
//   4. NB_DRY_RUN=1（或 DRY_RUN=1）：只跑断言、不删除、不 spawn 任何服务 —— 供下游负控实跑。
//   5. 确需删非 tmp 的隔离 home：NEBFLOW_CLEAN_ALLOW_ROOT=<绝对路径> + CONFIRM_DELETE=yes-i-mean-it
//      （默认不放行；即使放行，`/`、`$HOME`、`/Users/<user>` 一类黑名单仍拒）。
//
// 陷阱处置：
//   * macOS tmpdir 是 symlink（/var/folders/… → /private/var/folders/…）⇒ 两侧都 realpath 后比较；
//   * realpathSync 对不存在的路径抛异常（脚本可能启动时才 mkdir HOME）⇒ 用「最深已存在祖先 realpath +
//     余下段」解析，绝不因异常跳过断言；
//   * symlink 逃逸（/tmp/x -> ~/.nebflow）⇒ realpath 揭穿后落在允许根之外 → 拒；
//   * 末尾斜杠 / 相对路径 / `..` 拼接 ⇒ 先 path.resolve 归一（词法），再对已存在前缀 realpath。
//
// 负控（无需启动任何服务）：
//   NB_DRY_RUN=1 NEBFLOW_HOME=~/.nebflow node scripts/smoke-nodename-refresh.mjs   # 期望 exit 2，不 spawn
//   NB_DRY_RUN=1 node scripts/smoke-nodename-refresh.mjs                           # 期望 exit 0，不 spawn
//   node scripts/lib/delguard.mjs ~/.nebflow                                        # 期望 exit 2（只断言）
//   node scripts/lib/delguard.mjs "$(mktemp -d)/x"                                  # 期望 exit 0（只断言）

import { existsSync, realpathSync, rmSync } from 'node:fs';
import { dirname, isAbsolute, resolve as pathResolve, sep } from 'node:path';
import { homedir, tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const MAX_PATH_LEN = 1024;

/** 守卫拒绝（携带解析后绝对路径，便于负控断言输出） */
export class DelguardRefused extends Error {
  constructor(message, { resolved = null, raw = null } = {}) {
    super(message);
    this.name = 'DelguardRefused';
    this.resolved = resolved;
    this.raw = raw;
  }
}

export function isDryRun(env = process.env) {
  return env.NB_DRY_RUN === '1' || env.DRY_RUN === '1';
}

export function isCleanOptIn(env = process.env) {
  return (env.CLEAN === '1' || env.NB_CLEAN === '1') && !env.KEEP;
}

function realpathOrNull(p) {
  try { return realpathSync(p); } catch { return null; }
}

/** 最长已存在祖先的 realpath（拆 `.`/`..` 用）；解析不出即抛，绝不静默放行 */
function realpathAncestor(abs) {
  let head = abs;
  const rest = [];
  while (!existsSync(head) && dirname(head) !== head) {
    rest.unshift(head.slice(head.lastIndexOf(sep) + 1));
    head = dirname(head);
  }
  const real = realpathOrNull(head);
  if (!real) throw new DelguardRefused(`无法解析路径（realpath 失败）：${abs}`, { raw: abs, resolved: null });
  return rest.length ? `${real === sep ? '' : real}${sep}${rest.join(sep)}` : real;
}

/**
 * 归一化解析：词法归一（. / .. / 重复斜杠 / 末尾斜杠）+ 已存在前缀 realpath。
 * 不存在的尾段保留；空/超长/无法解析 → 抛 DelguardRefused。
 */
export function resolveReal(input) {
  if (typeof input !== 'string' || input.trim() === '') {
    throw new DelguardRefused('空路径 —— 拒绝任何递归删除', { raw: input ?? null });
  }
  if (input.length > MAX_PATH_LEN) {
    throw new DelguardRefused(`路径过长（${input.length} > ${MAX_PATH_LEN}）`, { raw: input });
  }
  const abs = isAbsolute(input) ? pathResolve(input) : pathResolve(process.cwd(), input);
  return realpathAncestor(abs);
}

/** 黑名单：命中即拒（即便落在允许根内/白名单内，这些根自身永不可删） */
function blacklistReason(resolved) {
  const segs = resolved.split(sep).filter(Boolean);
  if (segs.length === 0) return '文件系统根';
  const home = realpathOrNull(homedir());
  if (home && resolved === home) return `用户 home 根（${resolved}）`;
  if (segs[0] === 'Users' && segs.length <= 2) return 'macOS 用户目录根';
  if (segs[0] === 'Volumes' && segs.length <= 2) return '卷根';
  if (segs[0] === 'home' && segs.length <= 2) return 'Linux 用户目录根';
  if (segs[0] === 'root' && segs.length === 1) return 'root home 根';
  if (segs[0] === 'System' && segs.length <= 2) return '系统目录根';
  return null;
}

/** 允许根：默认 realpath(os.tmpdir())；显式白名单须二次确认（默认不放行） */
function resolveAllowRoot({ allowedRoot, env }) {
  const tmpRoot = realpathOrNull(tmpdir());
  if (!tmpRoot) throw new DelguardRefused('无法解析 os.tmpdir()（realpath 失败）——拒绝任何递归删除');
  const explicit = allowedRoot || env.NEBFLOW_CLEAN_ALLOW_ROOT || null;
  if (!explicit) return { root: tmpRoot, source: 'tmpdir' };
  const root = resolveReal(explicit);
  if (root === tmpRoot) return { root, source: 'tmpdir' };
  const bl = blacklistReason(root);
  if (bl) throw new DelguardRefused(`白名单根本身命中黑名单（${bl}）：${root}`, { raw: explicit, resolved: root });
  if (env.CONFIRM_DELETE !== 'yes-i-mean-it') {
    throw new DelguardRefused(
      `声明了非 tmp 允许根但缺 CONFIRM_DELETE=yes-i-mean-it → 拒绝（默认不放行）：${root}`,
      { raw: explicit, resolved: root });
  }
  console.warn(`[delguard] 非 tmp 允许根白名单生效（NEBFLOW_CLEAN_ALLOW_ROOT + CONFIRM_DELETE）：${root}`);
  return { root, source: 'explicit' };
}

/**
 * 硬断言（负控可单独调用）。通过 → 返回解析后绝对路径；不通过 → 抛 DelguardRefused（含 .resolved）。
 */
export function assertCleanable(target, { allowedRoot = null, env = process.env, label = 'delguard' } = {}) {
  const resolved = resolveReal(target);
  const bl = blacklistReason(resolved);
  if (bl) {
    throw new DelguardRefused(`[${label}] 命中黑名单（${bl}）→ 拒绝递归删除 resolved=${resolved}`,
      { raw: target, resolved });
  }
  const allow = resolveAllowRoot({ allowedRoot, env });
  if (resolved === allow.root) {
    throw new DelguardRefused(`[${label}] 拒绝删除允许根自身 resolved=${resolved}`, { raw: target, resolved });
  }
  if (!resolved.startsWith(allow.root + sep)) {
    throw new DelguardRefused(
      `[${label}] 不在允许根之下 → 拒绝递归删除 resolved=${resolved} allowedRoot=${allow.root}（source=${allow.source}）`,
      { raw: target, resolved });
  }
  return resolved;
}

/**
 * 断言 + 删除（删除点二次断言）。**不抛异常**（cleanup 里也用）——拒绝/跳过都返回结果对象。
 * 默认不删：需 CLEAN=1 / NB_CLEAN=1；DRY_RUN 只打印；KEEP 兼容保留。
 */
export function safeRm(target, { allowedRoot = null, env = process.env, label = 'delguard' } = {}) {
  let resolved = null;
  try {
    resolved = assertCleanable(target, { allowedRoot, env, label });
  } catch (e) {
    console.error(`[delguard] 拒绝删除（${label}）：${e.message}`);
    return { resolved: e.resolved ?? null, deleted: false, skipped: 'refused' };
  }
  if (isDryRun(env)) {
    console.log(`[delguard] DRY_RUN — 跳过删除 resolved=${resolved}（${label}）`);
    return { resolved, deleted: false, skipped: 'dry-run' };
  }
  if (env.KEEP) {
    console.log(`[delguard] KEEP=1 — 保留 resolved=${resolved}（${label}）`);
    return { resolved, deleted: false, skipped: 'legacy-keep' };
  }
  if (!isCleanOptIn(env)) {
    console.log(`[delguard] 默认不删 — 保留 resolved=${resolved}（${label}；要清理设 CLEAN=1，NB_DRY_RUN=1 只跑断言）`);
    return { resolved, deleted: false, skipped: 'not-opt-in' };
  }
  rmSync(resolved, { recursive: true, force: true });
  console.log(`[delguard] 已删除 resolved=${resolved}（${label}）`);
  return { resolved, deleted: true, skipped: null };
}

/**
 * 早期 fail-fast 入口（HOME 解析出之后、任何 spawn/网络动作之前调用）：
 *   断言失败 → stderr 打印 raw + 解析后绝对路径 → exit 2（非零），绝不降级；
 *   NB_DRY_RUN=1 → 只跑断言，打印结果后 exit 0（不删除、不 spawn）；
 *   通过 → 返回 { home, resolved, dryRun, cleanOptIn }。
 */
export function guardFixtureHome(home, { allowedRoot = null, env = process.env, label = 'delguard' } = {}) {
  let resolved;
  try {
    resolved = assertCleanable(home, { allowedRoot, env, label });
  } catch (e) {
    console.error(`[delguard] ABORT（${label}）：${e.message}`);
    console.error(`[delguard] ABORT（${label}）：raw=${String(e.raw)} resolved=${e.resolved ?? '<未解析>'}`);
    process.exit(2);
  }
  const dryRun = isDryRun(env);
  console.log(`[delguard] 允许根断言通过（${label}）：resolved=${resolved} dryRun=${dryRun} cleanOptIn=${isCleanOptIn(env)}`);
  if (dryRun) {
    console.log(`[delguard] NB_DRY_RUN=1 — 只跑断言：不删除、不启动任何服务/子进程（${label}）`);
    process.exit(0);
  }
  return { home, resolved, dryRun, cleanOptIn: isCleanOptIn(env) };
}

// ── 独立探测入口（只断言，不删除）：node scripts/lib/delguard.mjs <路径> [<路径>...] ──
// 两侧都取 realpath 比对，避开 macOS tmpdir symlink（/var/… vs /private/var/…）导致的漏入。
const SELF_REAL = realpathOrNull(fileURLToPath(import.meta.url)) ?? fileURLToPath(import.meta.url);
const ARGV_REAL = process.argv[1] ? (realpathOrNull(process.argv[1]) ?? process.argv[1]) : null;
if (ARGV_REAL && ARGV_REAL === SELF_REAL) {
  const targets = process.argv.slice(2);
  if (!targets.length) {
    console.error('用法: node scripts/lib/delguard.mjs <路径> [...]  （只跑断言，不删除；rc=0 通过 / rc=2 拒绝）');
    process.exit(64);
  }
  let refused = 0;
  for (const t of targets) {
    try {
      const r = assertCleanable(t, { label: 'probe' });
      console.log(`OK       raw=${t} resolved=${r} cleanOptIn=${isCleanOptIn()}`);
    } catch (e) {
      refused++;
      console.error(`REFUSED  raw=${t} resolved=${e.resolved ?? '<未解析>'} reason=${e.message}`);
    }
  }
  process.exit(refused ? 2 : 0);
}

export default { assertCleanable, safeRm, guardFixtureHome, resolveReal, isDryRun, isCleanOptIn, DelguardRefused };
