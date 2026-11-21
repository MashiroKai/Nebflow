#!/usr/bin/env node
// check-wt-cow — M1 闸：本机写时复制建树（workspace-compute R1a，作者令 2026-11-19）
//
// 判据（实施文档《虚拟工作区与多设备测试spec实施方案与可行性评估》M1 断言面 ①–④）：
//   ① 同一 Git tree 建「原生」与「写时复制」两棵：索引（git ls-files --stage）、HEAD、
//      逐文件内容、模式（可执行位）、符号链接目标逐项相同；
//   ② A 树写文件 / 原子替换 / 改权限 / 删 / 改名 / add / commit / 切分支之后，种子、
//      原生对照树与其它写时复制树一字不变；
//   ③ 种子来源是**对象库**而非某份工作拷贝：种子由 git archive 落盘，抽样文件 sha256
//      等于 git cat-file blob，工作树索引与 HEAD tree 完全一致；
//   ④ 物理增量：单棵写时复制建树 ≤2 MiB，且 9 棵合计 ≤ 原生对照单棵的 30%。
// 另加回退面：不能证明等价的输入必须显式退回原生并报原因（跨卷 / 检出属性 / 半途失败）。
//
// 物理量的取法（这条决定了本闸为什么用稀疏镜像）：本机主卷上 `df` 的读数噪声实测
// 达 ±150 MiB（并行会话在写盘），用它判 2 MiB 门槛等于掷硬币。所以整轮测量在**隔离
// 的 APFS 稀疏镜像**里做 —— 镜像里只有本次写入，读数稳定；镜像与外层同盘，用完即卸载
// 删除，不留存量树。
//
// 负控（作者令「故意破坏 COW 路径后闸必红」）：`STAND_WT_DEGRADE=copy|wrong-seed|partial`
// 由被测实现读取（`scripts/stand-wt-cow.py` 的测试钩子），本闸原样透传。因此
//   STAND_WT_DEGRADE=copy       node scripts/check-wt-cow.mjs ⇒ ①②③ 绿、④ 必红
//   STAND_WT_DEGRADE=wrong-seed node scripts/check-wt-cow.mjs ⇒ ①③ 必红（回退面反而绿）
// 非 darwin：clonefile(2) 不存在 ⇒ 退出码 2 显式 SKIP（run-checks 记为 SKIP，绝不假绿）。
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  chmodSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readlinkSync,
  renameSync, rmSync, symlinkSync, writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const TOOL = join(ROOT, 'scripts', 'stand-wt.py');
const COW = join(ROOT, 'scripts', 'stand-wt-cow.py');
const PY = process.env.PYTHON || 'python3';

// 夹具规模：600 个 32 KiB 文件 ≈ 19 MiB 内容。取这个量级是因为判据要能分辨
// 「共享数据块」与「真拷贝」两种形态，而元数据开销（每文件约 200 B）不能盖过内容量。
const FILES = 600;
const PAYLOAD = 32 * 1024;
const COW_TREES = 9;           // 判据④的「第 2 至第 10 棵」
const PER_TREE_CAP_KIB = 2048; // 判据④：单棵 ≤2 MiB
const RATIO_PCT = 30;          // 判据④：9 棵合计 ≤ 原生对照的 30%

const failures = [];
const readings = [];

function fail(id, detail) {
  failures.push(`${id}: ${detail}`);
  console.log(`FAIL ${id} — ${detail}`);
}

function pass(id, detail) {
  console.log(`PASS ${id} — ${detail}`);
}

function sh(file, args, options = {}) {
  return execFileSync(file, args, { encoding: 'utf8', cwd: options.cwd, stdio: ['ignore', 'pipe', 'pipe'] }).toString().trim();
}

function git(cwd, ...args) {
  return sh('git', args, { cwd });
}

function dfAvail(mount) {
  const line = sh('df', ['-k', mount]).split('\n')[1];
  return Number(line.split(/\s+/)[3]);
}

// ── 夹具 ────────────────────────────────────────────────────────────────────

function buildFixture(repo) {
  mkdirSync(repo, { recursive: true });
  git(repo, 'init', '-q', '-b', 'main');
  git(repo, 'config', 'user.email', 'gate@example.invalid');
  git(repo, 'config', 'user.name', 'wt-cow-gate');
  writeFileSync(join(repo, '.gitignore'), '/.stand/\n');
  // 检出属性一例（eol=crlf）：证明种子走的是**检出语义**，不是对象库原样字节。
  writeFileSync(join(repo, '.gitattributes'), '*.cmd text eol=crlf\n');
  writeFileSync(join(repo, 'run.cmd'), 'echo a\necho b\n');
  mkdirSync(join(repo, 'bin'), { recursive: true });
  writeFileSync(join(repo, 'bin', 'tool.sh'), '#!/bin/sh\necho hi\n');
  chmodSync(join(repo, 'bin', 'tool.sh'), 0o755);
  mkdirSync(join(repo, 'sub'), { recursive: true });
  symlinkSync('../run.cmd', join(repo, 'sub', 'link.cmd'));
  for (let i = 0; i < FILES; i += 1) {
    const dir = join(repo, 'd' + String(Math.floor(i / 50)).padStart(2, '0'));
    mkdirSync(dir, { recursive: true });
    const body = Buffer.concat([Buffer.from(`file-${i}\n`), Buffer.alloc(PAYLOAD, String.fromCharCode(97 + (i % 26)))]);
    writeFileSync(join(dir, `f${i}.bin`), body);
  }
  git(repo, 'add', '-A');
  git(repo, 'commit', '-qm', 'seed');
  git(repo, 'branch', 'feat/base');
  return git(repo, 'rev-parse', 'HEAD^{tree}');
}

// ── 测量与比对工具 ──────────────────────────────────────────────────────────

function inventory(directory) {
  const out = new Map();
  const walk = (dir, prefix) => {
    const entries = execFileSync('find', [dir, '-mindepth', '1', '-maxdepth', '1'], { encoding: 'utf8' })
      .split('\n').filter(Boolean).sort();
    for (const entry of entries) {
      const rel = prefix ? `${prefix}/${entry.slice(dir.length + 1)}` : entry.slice(dir.length + 1);
      if (rel === '.git' || rel.startsWith('.git/')) continue;
      const st = lstatSync(entry);
      if (st.isDirectory()) walk(entry, rel);
      else if (st.isSymbolicLink()) out.set(rel, `link:${readlinkSync(entry)}`);
      else out.set(rel, `${(st.mode & 0o111) ? 'x' : '-'}:${createHash('sha256').update(readFileSync(entry)).digest('hex')}`);
    }
  };
  walk(directory, '');
  return out;
}

function diffInventories(a, b) {
  const problems = [];
  for (const [path, value] of a) {
    if (!b.has(path)) problems.push(`missing in B: ${path}`);
    else if (b.get(path) !== value) problems.push(`differs: ${path}`);
  }
  for (const path of b.keys()) if (!a.has(path)) problems.push(`extra in B: ${path}`);
  return problems;
}

function digestOf(map) {
  const hash = createHash('sha256');
  for (const [k, v] of [...map.entries()].sort()) hash.update(`${k}\0${v}\n`);
  return hash.digest('hex').slice(0, 16);
}

function newTree(repo, mount, name, backend) {
  const before = dfAvail(mount);
  const started = process.hrtime.bigint();
  const output = sh(PY, ['-B', TOOL, '--root', repo, 'new', name, '--backend', backend]);
  const elapsedMs = Number(process.hrtime.bigint() - started) / 1e6;
  const after = dfAvail(mount);
  const line = output.split('\n').find(l => l.startsWith('Backend:')) || '';
  return {
    name, line, elapsedMs,
    deltaKib: before - after,
    path: join(repo, '.stand', 'worktrees', name),
    reportedBackend: line.startsWith('Backend: cow') ? 'cow' : 'native',
    reason: (line.match(/fallback: ([a-z-]+)/) || [])[1] || null,
  };
}

// ── 主流程 ─────────────────────────────────────────────────────────────────

function main() {
  if (process.platform !== 'darwin') {
    console.error(`check-wt-cow: SKIP — platform=${process.platform}：clonefile(2) 不存在，写时复制后端不可用（这不是通过，是显式跳过）`);
    process.exit(2);
  }
  if (!existsSync('/usr/bin/hdiutil')) {
    console.error('check-wt-cow: SKIP — hdiutil 缺失：无法建隔离稀疏镜像做无噪声物理量测量');
    process.exit(2);
  }

  const work = mkdtempSync(join(tmpdir(), 'wtcow-gate-'));
  const mount = join(work, 'vol');
  const image = join(work, 'gate.sparseimage');
  mkdirSync(mount, { recursive: true });
  let attached = false;
  try {
    sh('hdiutil', ['create', '-fs', 'APFS', '-size', '500m', '-type', 'SPARSE', '-volname', 'wtcowgate', image, '-quiet']);
    sh('hdiutil', ['attach', image, '-nobrowse', '-mountpoint', mount, '-quiet']);
    attached = true;
    const degrade = process.env.STAND_WT_DEGRADE || '';
    console.log(`check-wt-cow: darwin · isolated APFS image at ${mount}` +
      (degrade ? ` · STAND_WT_DEGRADE=${degrade}（负控运行：判据应当变红）` : ''));

    const repo = join(mount, 'repo');
    const tree = buildFixture(repo);
    console.log(`fixture: tree=${tree} files=${FILES + 3} payload≈${Math.round((FILES * PAYLOAD) / 1048576)} MiB`);

    // ── ① 等价性：原生对照树 vs 写时复制树 ──
    // 先把种子单独建出来并计一次读数：种子是**每棵树一次**的固定成本（约等于一棵原生
    // 树的内容量）。若把它混进第一棵 COW 树的窗口，就会把它读成「全量复制」—— 而区分
    // 这两种形态正是本闸的职责。
    const seedStarted = process.hrtime.bigint();
    const seedReport = JSON.parse(sh(PY, ['-B', COW, 'seed', repo, tree]));
    const seedMs = Number(process.hrtime.bigint() - seedStarted) / 1e6;
    readings.push(`seed one-time = ${(seedReport.index.bytes / 1048576).toFixed(1)} MiB / ${Math.round(seedMs)} ms (files=${seedReport.index.files})`);
    const nativeTree = newTree(repo, mount, 'native-ref', 'native');
    const cow1 = newTree(repo, mount, 'cow-1', 'cow');
    if (nativeTree.reportedBackend !== 'native') fail('①-native-baseline', `expect backend=native, got ${nativeTree.line}`);
    if (cow1.reportedBackend !== 'cow') fail('①-cow-backend', `expect backend=cow, got ${cow1.line}`);
    const indexNative = git(nativeTree.path, 'ls-files', '--stage');
    const indexCow = git(cow1.path, 'ls-files', '--stage');
    if (indexNative !== indexCow) fail('①-index', 'git ls-files --stage differs between native and cow trees');
    const headNative = git(nativeTree.path, 'rev-parse', 'HEAD^{tree}');
    const headCow = git(cow1.path, 'rev-parse', 'HEAD^{tree}');
    if (headNative !== headCow || headCow !== tree) fail('①-head', `HEAD tree: native=${headNative} cow=${headCow} want=${tree}`);
    const statusCow = git(cow1.path, 'status', '--porcelain');
    if (statusCow) fail('①-clean', `cow tree not clean: ${statusCow.split('\n').slice(0, 3).join(' | ')}`);
    const invNative = inventory(nativeTree.path);
    const invCow = inventory(cow1.path);
    const invProblems = diffInventories(invNative, invCow);
    if (invProblems.length) fail('①-content', `${invProblems.length} differences, e.g. ${invProblems.slice(0, 3).join('; ')}`);
    if (readlinkSync(join(cow1.path, 'sub', 'link.cmd')) !== '../run.cmd') fail('①-symlink', 'symlink target not preserved');
    if (!(lstatSync(join(cow1.path, 'bin', 'tool.sh')).mode & 0o111)) fail('①-exec', 'executable bit lost');
    if (!readFileSync(join(cow1.path, 'run.cmd')).includes(0x0d)) fail('①-eol', 'eol=crlf attribute not applied to run.cmd');
    if (!failures.some(f => f.startsWith('①'))) {
      pass('①-equivalence', `${invCow.size} paths · index/HEAD/content/mode/symlink 与原生对照树逐项相同（native ${invNative.size} paths）`);
    }
    readings.push(`native per-tree = ${nativeTree.deltaKib} KiB / ${Math.round(nativeTree.elapsedMs)} ms`);
    readings.push(`cow-1 per-tree  = ${cow1.deltaKib} KiB / ${Math.round(cow1.elapsedMs)} ms`);

    // ── ③ 种子来源：对象库 → 检出语义；抽样 sha256 等于 cat-file blob ──
    const treeSha = git(repo, 'rev-parse', 'HEAD^{tree}');
    const seedDir = join(repo, '.stand', 'seeds', treeSha);
    const seedIndex = join(repo, '.stand', 'seeds', `${treeSha}.json`);
    if (!existsSync(seedDir) || !existsSync(seedIndex)) fail('③-seed', 'seed directory or index missing');
    else {
      const meta = JSON.parse(readFileSync(seedIndex, 'utf8'));
      if (meta.tree !== treeSha || meta.format !== 1 || meta.builder !== 'git-archive') fail('③-seed-index', JSON.stringify(meta));
      const samples = ['run.cmd', 'bin/tool.sh', 'd00/f0.bin', 'd11/f599.bin'].filter(p => existsSync(join(seedDir, p)));
      const mismatched = samples.filter(p => {
        const blob = execFileSync('git', ['cat-file', 'blob', `HEAD:${p}`], { cwd: repo, encoding: 'buffer' });
        const onDisk = readFileSync(join(seedDir, p));
        if (p.endsWith('.cmd')) return !onDisk.equals(Buffer.from(blob.toString().replace(/\n/g, '\r\n')));
        return !onDisk.equals(blob);
      });
      if (mismatched.length) fail('③-seed-content', `seed sample mismatch vs git cat-file: ${mismatched.join(', ')}`);
      else {
        pass('③-seed-provenance', `seed files=${meta.files} bytes=${meta.bytes} builder=${meta.builder} · ${samples.length} 个抽样文件 sha256 等于 git cat-file blob（含 eol=crlf 检出语义）`);
      }
    }

    // ── ④ 物理增量：9 棵写时复制树 vs 原生对照 ──
    const cowTrees = [cow1];
    for (let i = 2; i <= COW_TREES; i += 1) cowTrees.push(newTree(repo, mount, `cow-${i}`, 'cow'));
    const total = cowTrees.reduce((sum, t) => sum + t.deltaKib, 0);
    const maxTree = cowTrees.reduce((worst, t) => (t.deltaKib > worst.deltaKib ? t : worst), cowTrees[0]);
    const ratio = nativeTree.deltaKib > 0 ? (total / nativeTree.deltaKib) * 100 : Infinity;
    readings.push(`${COW_TREES} cow trees total = ${total} KiB · max per-tree = ${maxTree.deltaKib} KiB (${maxTree.name}) · ${ratio.toFixed(1)}% of native ${nativeTree.deltaKib} KiB`);
    if (maxTree.deltaKib > PER_TREE_CAP_KIB) fail('④-per-tree', `${maxTree.name} = ${maxTree.deltaKib} KiB > ${PER_TREE_CAP_KIB} KiB`);
    if (ratio > RATIO_PCT) fail('④-ratio', `${COW_TREES} trees = ${total} KiB = ${ratio.toFixed(1)}% of native (${nativeTree.deltaKib} KiB) > ${RATIO_PCT}%`);
    if (!failures.some(f => f.startsWith('④'))) {
      pass('④-disk', `${COW_TREES} 棵合计 ${total} KiB = 原生对照的 ${ratio.toFixed(1)}%（门槛 ≤${RATIO_PCT}%）· 单棵最大 ${maxTree.deltaKib} KiB ≤ ${PER_TREE_CAP_KIB} KiB`);
    }

    // ── ② 写入隔离：改动 A 树不得动到种子、原生树与其它 COW 树 ──
    const seedBefore = digestOf(inventory(seedDir));
    const witnessNative = digestOf(inventory(nativeTree.path));
    const witnessSibling = digestOf(inventory(cowTrees[2].path));
    writeFileSync(join(cow1.path, 'd00', 'f0.bin'), 'mutated\n');
    writeFileSync(join(cow1.path, 'd00', '.f1.tmp'), 'atomic\n');
    renameSync(join(cow1.path, 'd00', '.f1.tmp'), join(cow1.path, 'd00', 'f1.bin'));
    chmodSync(join(cow1.path, 'bin', 'tool.sh'), 0o700);
    rmSync(join(cow1.path, 'd01', 'f50.bin'));
    renameSync(join(cow1.path, 'd01', 'f51.bin'), join(cow1.path, 'd01', 'renamed.bin'));
    writeFileSync(join(cow1.path, 'added.txt'), 'added\n');
    git(cow1.path, 'add', '-A');
    git(cow1.path, 'commit', '-qm', 'mutations');
    git(cow1.path, 'checkout', '-q', '-b', 'side');
    if (digestOf(inventory(seedDir)) !== seedBefore) fail('②-seed-immutable', 'seed changed after writes in the cow tree');
    if (digestOf(inventory(nativeTree.path)) !== witnessNative) fail('②-native-immutable', 'native reference tree changed');
    if (digestOf(inventory(cowTrees[2].path)) !== witnessSibling) fail('②-sibling-immutable', 'sibling cow tree changed');
    if (!failures.some(f => f.startsWith('②'))) {
      pass('②-isolation', '写 / 原子替换 / chmod / 删 / 改名 / add / commit / 切分支后：种子、原生树、兄弟 COW 树摘要均不变');
    }

    // ── 回退面 A：检出属性（filter=/export-ignore/export-subst）⇒ 显式退回原生 ──
    const filtered = join(mount, 'repo-filter');
    mkdirSync(filtered);
    git(filtered, 'init', '-q', '-b', 'main');
    git(filtered, 'config', 'user.email', 'gate@example.invalid');
    git(filtered, 'config', 'user.name', 'wt-cow-gate');
    writeFileSync(join(filtered, '.gitignore'), '/.stand/\n');
    writeFileSync(join(filtered, '.gitattributes'), '*.txt filter=derived\n');
    writeFileSync(join(filtered, 'a.txt'), 'a\n');
    git(filtered, 'add', '-A');
    git(filtered, 'commit', '-qm', 'filtered');
    git(filtered, 'branch', 'feat/base');
    const filteredTree = newTree(filtered, mount, 'f1', 'cow');
    if (filteredTree.reportedBackend !== 'native' || filteredTree.reason !== 'filter-attributes') {
      fail('fallback-filter', `expect native/filter-attributes, got ${filteredTree.line}`);
    } else if (git(filteredTree.path, 'status', '--porcelain')) {
      fail('fallback-filter-tree', 'fallback tree is not a valid checkout');
    } else {
      pass('fallback-filter', `检出属性触发器 ⇒ ${filteredTree.line}，回退树校验通过`);
    }

    // ── 回退面 B：跨卷（种子与目标不同卷）⇒ 模块级 preflight 必须拒绝 ──
    // 种子放在镜像卷上、目标放在外层主卷上：两个 volume id 不同，preflight 必须在
    // 动手之前拒绝，而不是让 `cp -c` 静默退化成全量拷贝。
    const crossVolume = sh(PY, ['-B', '-c', `
import importlib.util, json
spec = importlib.util.spec_from_file_location("cow", r"${COW}")
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
ok, reason = m.preflight(r"${ROOT}", r"${join(mount, 'seeds-probe')}", r"${join(work, 'cross', 'x')}", "${tree}")
print(json.dumps({"ok": ok, "reason": reason}))
`]);
    const verdict = JSON.parse(crossVolume);
    if (verdict.ok !== false || verdict.reason !== 'cross-volume') fail('fallback-cross-volume', `expect refuse/cross-volume, got ${crossVolume}`);
    else pass('fallback-cross-volume', '种子卷 ≠ 目标卷 ⇒ preflight 拒绝（reason=cross-volume），不会静默退化成全量拷贝');

    // ── 回退面 C：半途失败（population 被截断）⇒ 丢弃重建为原生，结果树仍正确 ──
    process.env.STAND_WT_DEGRADE = 'partial';
    const partial = newTree(repo, mount, 'cow-partial', 'cow');
    delete process.env.STAND_WT_DEGRADE;
    if (partial.reportedBackend !== 'native' || partial.reason !== 'verify-failed') {
      fail('fallback-partial', `expect native/verify-failed, got ${partial.line}`);
    } else if (git(partial.path, 'status', '--porcelain') || git(partial.path, 'ls-files', '--stage') !== indexNative) {
      fail('fallback-partial-tree', 'recovered tree is not a valid checkout');
    } else {
      pass('fallback-partial', `半途失败 ⇒ ${partial.line}，丢弃后重建的原生树索引与对照树一致`);
    }
  } finally {
    if (attached) {
      try { sh('hdiutil', ['detach', mount, '-quiet']); } catch { /* 卸载失败不掩盖判据结论 */ }
    }
    rmSync(work, { recursive: true, force: true });
    console.log(`cleanup: image and fixture removed (${work})`);
  }

  console.log('\nreadings:');
  for (const line of readings) console.log(`  · ${line}`);
  if (failures.length) {
    console.log(`\ncheck-wt-cow: FAILED (${failures.length} criterion failures)`);
    process.exit(1);
  }
  console.log('\ncheck-wt-cow: ok — ①等价 ②隔离 ③种子来源 ④物理增量 + 三条回退面 全绿');
}

main();
