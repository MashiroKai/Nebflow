// git-graph.mjs — 只读 git 图数据面（MVP 数据源；设计稿 P0 ①「一条只读路由」的
// 参考实现）。契约所有者 = 本文件；落地时后端 Scala 路由 `GET /api/projects/<name>/
// git-graph` 逐字段输出同一 JSON（设计稿 §七 数据来源表：端点声明 nodeData.js:23、
// 挂载点 ProjectsRoutes.scala:25）。
//
// 🔴 零写面：本文件只用 `git log` / `for-each-ref` / `rev-list` / `merge-base`
// （全部只读子命令），不执行任何 checkout / merge / branch / commit / fetch。
// 子命令白名单在 READ_ONLY_GIT 里硬编码，调用点只能从中取（越界即抛错）。
//
// 泳道算法（后端算泳道、前端只画，设计稿 §七「大仓性能预算」）：
//   1. 取本仓 local refs（按 committerdate 降序），HEAD 所在支永远占 0 号泳道；
//   2. 只画前 `lanes` 条支（默认 8），其余支只出现在 refs 清单（`refsShown < refsTotal`
//      如实上报，不假装画全）；
//   3. 提交序列 = `git log --date-order -n <limit>` 仅在这几条支上；
//   4. 泳道归属：支 tip ⇒ 该支泳道；否则继承「已处理子提交留给它」的泳道（merge 的
//      第二父支另外留一条 pending 泳道）；
//   5. 热力图 = `git log --all --since=<days> --date=short` 的按日计数（全仓真实读数）。

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';

/** 只读子命令白名单（唯一取用口；写命令进不来）。 */
export const READ_ONLY_GIT = new Set([
  'for-each-ref',
  'log',
  'rev-list',
  'merge-base',
  'rev-parse',
  'symbolic-ref',
  'tag',
]);

const FIELD = '\x1f';
const REC = '\x1e';

/** @param {string} root @param {string[]} args */
function git(root, args) {
  if (!READ_ONLY_GIT.has(args[0])) throw new Error(`gitviz: refused non-read-only git subcommand "${args[0]}"`);
  return execFileSync('git', ['-C', root, ...args], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}

function gitSoft(root, args) {
  try {
    return { ok: true, out: git(root, args) };
  } catch (e) {
    return { ok: false, out: '', err: String(e && e.message ? e.message : e) };
  }
}

/** @param {number} ms */
function isoDay(ms) {
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** 本地分支清单（真实读数）。@returns {Array<{name:string,tip:string,ts:number,isHead:boolean}>} */
function localBranches(root) {
  const out = git(root, [
    'for-each-ref',
    '--sort=-committerdate',
    `--format=%(refname:short)${FIELD}%(objectname)${FIELD}%(committerdate:unix)${FIELD}%(HEAD)`,
    'refs/heads',
  ]);
  return out
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .map((line) => {
      const [name, tip, ts, head] = line.split(FIELD);
      return { name, tip, ts: Number(ts) * 1000, isHead: (head || '').trim() === '*' };
    });
}

/** 全部 ref（本地支 + 远端支 + tag）→ 按 commit sha 归并的标签表。 */
function allRefLabels(root) {
  const out = gitSoft(root, ['for-each-ref', `--format=%(objectname)${FIELD}%(refname:short)`, 'refs/heads', 'refs/remotes', 'refs/tags']);
  /** @type {Map<string, string[]>} */
  const bySha = new Map();
  if (!out.ok) return bySha;
  for (const line of out.out.split('\n')) {
    if (!line.trim()) continue;
    const [sha, name] = line.split(FIELD);
    if (!sha || !name) continue;
    const arr = bySha.get(sha) || [];
    arr.push(name);
    bySha.set(sha, arr);
  }
  return bySha;
}

/**
 * 近 N 天按日 commit 计数（`git log --all --date=short`，真实读数）。
 * @returns {{days: Array<{date:string,count:number}>, total:number, max:number, from:string, to:string}}
 */
export function heatmap(root, days = 84) {
  const now = Date.now();
  const since = new Date(now - (days - 1) * 86400000);
  const out = gitSoft(root, ['log', '--all', `--since=${isoDay(since.getTime())}`, '--date=short', '--pretty=%ad', '--no-merges']);
  /** @type {Map<string, number>} */
  const counts = new Map();
  if (out.ok) {
    for (const line of out.out.split('\n')) {
      const d = line.trim();
      if (d) counts.set(d, (counts.get(d) || 0) + 1);
    }
  }
  const list = [];
  for (let i = days - 1; i >= 0; i--) {
    const date = isoDay(now - i * 86400000);
    list.push({ date, count: counts.get(date) || 0 });
  }
  const total = list.reduce((a, b) => a + b.count, 0);
  const max = list.reduce((a, b) => Math.max(a, b.count), 0);
  return { days: list, total, max, from: list[0]?.date || '', to: list[list.length - 1]?.date || '' };
}

/**
 * 主入口：只读构建 git 图载荷。
 * @param {{root:string, limit?:number, lanes?:number, heatDays?:number}} opts
 */
export function buildGitGraph(opts) {
  const root = opts.root;
  const limit = Math.max(20, Math.min(2000, opts.limit ?? 1200));
  const maxLanes = Math.max(2, Math.min(12, opts.lanes ?? 8));
  const heatDays = Math.max(28, Math.min(371, opts.heatDays ?? 84));

  const repoCheck = gitSoft(root, ['rev-parse', '--is-inside-work-tree']);
  if (!repoCheck.ok || repoCheck.out.trim() !== 'true') {
    return { state: 'not-a-repo', root, message: '不是 git 工作树（只读探针 git rev-parse --is-inside-work-tree 未通过）' };
  }

  const branches = localBranches(root);
  const headName = branches.find((b) => b.isHead)?.name || '';
  const headSha = gitSoft(root, ['rev-parse', 'HEAD']).out.trim();

  // 0 号泳道 = HEAD 所在支；其余按 committerdate 降序填满 lanes 档。
  const ordered = [];
  const headBranch = branches.find((b) => b.name === headName);
  if (headBranch) ordered.push(headBranch);
  for (const b of branches) {
    if (ordered.length >= maxLanes) break;
    if (b.name !== headName) ordered.push(b);
  }
  const laneOf = new Map(ordered.map((b, i) => [b.name, i]));

  const refArgs = ordered.map((b) => b.name).filter(Boolean);
  // 一次 `git log --date-order -n <limit>` 覆盖全部画出的支（窗口要够深才看得见
  // 分叉点与合流点；本仓 8 支各领先基线 ≈185 提交 ⇒ 默认 limit 取 1200 档，
  // 仍在设计稿「首屏 ≤ 2,000 提交」预算内），随后**逐个 tip 兜底**：
  // 🔴 多支共用 `-n` 时窗口按提交时间切，tip 更旧的支会被整段切掉（本批首轮实测：
  //   HEAD 的 tip 不在 140 窗内 ⇒ 泳道 0 空转、图上画不出基线）。故任何缺席的
  //   tip 单独补取并前置到行首（诚实口径：踢出窗外的只有更老的中间提交）。
  const logFmt = `--pretty=format:%H${FIELD}%P${FIELD}%at${FIELD}%an${FIELD}%s${REC}`;
  const parse = (out) => out
    .split(REC)
    .map((r) => r.replace(/^\n+/, '').trim())
    .filter(Boolean)
    .map((r) => {
      const [sha, parents, at, an, ...subj] = r.split(FIELD);
      return { sha, parents: (parents || '').split(' ').filter(Boolean), ts: Number(at) * 1000, author: an || '', subject: subj.join(FIELD) };
    });
  // 不带 `--`：路径分隔符会把 ref 当路径，log 变空。
  const walked = refArgs.length
    ? parse(git(root, ['log', '--date-order', '-n', String(limit), logFmt, ...refArgs]))
    : [];
  /** @type {Map<string, {sha:string,parents:string[],ts:number,author:string,subject:string}>} */
  const byShaAll = new Map(walked.map((c) => [c.sha, c]));
  const missingTips = ordered.map((b) => b.tip).filter((t) => t && !byShaAll.has(t));
  for (const tip of missingTips) {
    const one = parse(gitSoft(root, ['log', '-n', '1', logFmt, tip]).out);
    if (one[0]) byShaAll.set(one[0].sha, one[0]);
  }
  const missingTipSet = new Set(missingTips);
  const commits = [...byShaAll.values()]
    .sort((a, b) => (missingTipSet.has(a.sha) ? 0 : 1) - (missingTipSet.has(b.sha) ? 0 : 1) || b.ts - a.ts)
    .slice(0, limit + missingTips.length);

  const labels = allRefLabels(root);

  // ── 泳道归属（**按支优先**，不是「先到先得」）────────────────────────────
  // 逐支按泳道序认领：0 = HEAD 所在支先认领它能到达的**全部**提交（基线因此是一条
  // 连续泳道，含与其余支共用的祖先），其余支再认领各自独有的段 ⇒ 图上读到的是
  // 「一条基线 + 若干分叉」，而不是「谁先遍历到谁当主线」。只在本窗内认领。
  const inWindow = new Set(commits.map((c) => c.sha));
  /** @type {Map<string, number>} */
  const laneBySha = new Map();
  const claimed = new Set();
  ordered.forEach((b, lane) => {
    /** @type {string[]} */
    const stack = [b.tip];
    while (stack.length) {
      const sha = stack.pop();
      if (claimed.has(sha) || !inWindow.has(sha)) continue;
      claimed.add(sha);
      laneBySha.set(sha, lane);
      const c = byShaAll.get(sha);
      if (c) for (const p of c.parents) if (!claimed.has(p) && inWindow.has(p)) stack.push(p);
    }
  });
  // 兜底：与任何 tip 都不连通的提交（不应发生，防静默丢点）归 0 号泳道
  for (const c of commits) if (!laneBySha.has(c.sha)) laneBySha.set(c.sha, 0);

  // 行序 = 新 → 旧（row 0 = 最新，顶部；git 图惯例），边一律向下（子 row < 父 row）。
  const nodes = commits.map((c, row) => ({
    sha: c.sha,
    short: c.sha.slice(0, 8),
    row,
    lane: laneBySha.get(c.sha) ?? 0,
    ts: c.ts,
    date: isoDay(c.ts),
    author: c.author,
    subject: c.subject,
    parents: c.parents.filter((p) => laneBySha.has(p)),
    refs: labels.get(c.sha) || [],
    isTip: (labels.get(c.sha) || []).some((n) => laneOf.has(n)),
  }));
  const rowBySha = new Map(nodes.map((n) => [n.sha, n.row]));

  /** 只对画出来的支算 ahead/behind 与 merge-base（真实读数；零写面）。 */
  const refModels = ordered.map((b, lane) => {
    let ahead = null;
    let behind = null;
    let mergeBase = '';
    if (headSha && b.tip !== headSha) {
      // `rev-list --left-right --count HEAD...ref` ⇒ 左 = 只在 HEAD（ref 落后量）、
      // 右 = 只在 ref（ref 领先量）。口径：ahead = ref 领先 HEAD 的提交数。
      const ab = gitSoft(root, ['rev-list', '--left-right', '--count', `${headSha}...${b.tip}`]);
      if (ab.ok) {
        const [left, right] = ab.out.trim().split(/\s+/);
        behind = Number(left);
        ahead = Number(right);
      }
      mergeBase = gitSoft(root, ['merge-base', headSha, b.tip]).out.trim();
    } else if (b.tip === headSha) {
      ahead = 0;
      behind = 0;
      mergeBase = b.tip;
    }
    return {
      name: b.name,
      kind: 'local',
      lane,
      tip: b.tip,
      tipShort: b.tip.slice(0, 8),
      ts: b.ts,
      isHead: b.tip === headSha,
      ahead,
      behind,
      mergeBase,
      inGraph: rowBySha.has(b.tip),
    };
  });

  const edges = [];
  for (const n of nodes) {
    for (const p of n.parents) {
      const prow = rowBySha.get(p);
      if (prow === undefined) continue;
      const pl = laneBySha.get(p) ?? 0;
      edges.push({ from: n.sha, to: p, fromLane: n.lane, toLane: pl, fromRow: n.row, toRow: prow });
    }
  }

  const heat = heatmap(root, heatDays);
  const payload = {
    state: 'ok',
    project: opts.project || '',
    root,
    generatedAt: Date.now(),
    head: { ref: headName, sha: headSha, short: headSha.slice(0, 8) },
    lanes: ordered.length,
    refsTotal: branches.length,
    refsShown: ordered.length,
    commitsTotal: commits.length,
    commitsLimit: limit,
    refs: refModels,
    commits: nodes,
    edges,
    heatmap: heat,
    // 载荷指纹：前端可据此判断「同一次读数」，也便于证据留档比对。
    digest: createHash('sha256').update(JSON.stringify({ refs: refModels.map((r) => r.name + r.tip), n: nodes.length })).digest('hex').slice(0, 12),
  };
  return payload;
}
