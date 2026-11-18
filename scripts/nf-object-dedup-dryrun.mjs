#!/usr/bin/env node
// nf-object-dedup-dryrun.mjs — 存储层对象去重「只读 dry-run」（按对象复用批
// 2026-11-18 · 第 2 件）。
//
// 硬约束（作者令逐字「本轮不得执行任何真实迁移或清理命令」）：本脚本**只读**。
//   它遍历已有的 `uploads/<session>/<file>` 树，算每件的整件 sha256，按内容分组，
//   输出「迁移会合并什么 / 能省多少字节 / 孤儿对象风险 / 磁盘峰值」的清单。
//   它**不**创建、**不**复制、**不**移动、**不**删除任何被扫描的字节；唯一写动作是
//   把清单写到 `--out` 指定的路径（缺省 = stdout）。
//
// 目标形态（第 2 件的目标）：`objects/<sha256 前2>/<sha256>` + 引用表
//   （引用 = 哪个会话目录的哪个文件名指向哪个对象）。
//
// 用法：
//   node scripts/nf-object-dedup-dryrun.mjs --root /Users/kaiyu/.stand/uploads \
//        --out /Users/kaiyu/stand-evidence/media-object-cid-20261118/dedup-dryrun.json
//
// 退出码：0 = 清单产出成功；2 = 参数/路径错误。
import { createHash } from 'node:crypto';
import { createReadStream, readdirSync, statSync, writeFileSync, lstatSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';
import { homedir } from 'node:os';

function parseArgs(argv) {
  const out = { root: null, out: null, extraRoots: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--root') out.root = argv[++i];
    else if (a === '--out') out.out = argv[++i];
    else if (a === '--also') out.extraRoots.push(argv[++i]);
    else if (a === '--help' || a === '-h') { console.log('usage: nf-object-dedup-dryrun.mjs [--root DIR] [--also DIR] [--out FILE]'); process.exit(0); }
    else { console.error('unknown arg: ' + a); process.exit(2); }
  }
  if (!out.root) out.root = join(homedir(), '.stand', 'uploads');
  return out;
}

const sha256HexOf = (file) =>
  new Promise((res, rej) => {
    const h = createHash('sha256');
    const s = createReadStream(file, { highWaterMark: 1 << 20 });
    s.on('data', (c) => h.update(c));
    s.on('end', () => res(h.digest('hex')));
    s.on('error', rej);
  });

/** 递归列出 root 下的普通文件（不跟随符号链接；符号链接/特殊件单列，永不跟随）。 */
function walk(root) {
  const files = [];
  const symlinks = [];
  const specials = [];
  const stack = [root];
  while (stack.length) {
    const dir = stack.pop();
    let entries;
    try { entries = readdirSync(dir, { withFileTypes: true }); } catch { continue; }
    for (const e of entries) {
      const p = join(dir, e.name);
      let st;
      try { st = lstatSync(p); } catch { continue; }
      if (st.isSymbolicLink()) { symlinks.push(p); continue; }
      if (st.isDirectory()) { stack.push(p); continue; }
      if (!st.isFile()) { specials.push(p); continue; }
      files.push({ path: p, rel: relative(root, p), size: st.size, mtimeMs: st.mtimeMs });
    }
  }
  return { files, symlinks, specials };
}

const h2 = (h) => h.slice(0, 2);
/** 临时/半成品不算对象（attach-uploads 的 `.part` 同族）。 */
const TEMP_RE = /\.(part|tmp|partial)$/i;

async function scan(root) {
  const { files, symlinks, specials } = walk(root);
  /** @type {Map<string, {sha256:string, bytes:number, copies:{rel:string, size:number, mtimeMs:number}[]}>} */
  const byHash = new Map();
  const errors = [];
  let totalBytes = 0;
  let tempFiles = 0;
  for (const f of files) {
    if (TEMP_RE.test(f.rel)) { tempFiles += 1; continue; }
    if (f.size === 0) continue;             // 空件不内容寻址（与 NfObjStore 下界一致）
    totalBytes += f.size;
    let digest;
    try { digest = await sha256HexOf(f.path); } catch (e) { errors.push({ rel: f.rel, error: String((e && e.message) || e) }); continue; }
    let g = byHash.get(digest);
    if (!g) { g = { sha256: digest, bytes: f.size, copies: [] }; byHash.set(digest, g); }
    g.copies.push({ rel: f.rel, size: f.size, mtimeMs: f.mtimeMs });
  }

  const groups = [...byHash.values()].sort((a, b) => b.copies.length - a.copies.length || b.bytes - a.bytes);
  const dupGroups = groups.filter((g) => g.copies.length > 1);
  const uniqueObjects = groups.length;
  const uniqueBytes = groups.reduce((s, g) => s + g.bytes, 0);
  const savedBytes = dupGroups.reduce((s, g) => s + g.bytes * (g.copies.length - 1), 0);
  const dupFiles = dupGroups.reduce((s, g) => s + g.copies.length, 0);

  // 同名不同内容（同会话目录 + 同文件名 → ≥2 个不同 sha256）：迁移必须带引用表消歧，
  // 否则 `objects/<sha256>` 的正向布局没问题，但「按旧路径回读」的反查会撞键。
  const byNameKey = new Map();
  for (const g of groups) {
    for (const c of g.copies) {
      if (!byNameKey.has(c.rel)) byNameKey.set(c.rel, new Set());
      byNameKey.get(c.rel).add(g.sha256);
    }
  }
  const samePathDifferentBytes = [...byNameKey.values()].filter((s) => s.size > 1).length;

  return {
    root,
    scannedAt: new Date().toISOString(),
    files: files.length,
    tempFilesSkipped: tempFiles,
    objectsCounted: groups.reduce((s, g) => s + g.copies.length, 0),
    totalBytes,
    symlinks: symlinks.length,
    specials: specials.length,
    readErrors: errors.length,
    uniqueObjects,
    uniqueBytes,
    duplicateGroups: dupGroups.length,
    duplicateFiles: dupFiles,
    savedBytes,
    savedPct: totalBytes > 0 ? Number(((savedBytes / totalBytes) * 100).toFixed(2)) : 0,
    // 峰值代价（步骤①「复制不删」）：旧树仍在 + 新对象树
    peakBytesEstimate: totalBytes + uniqueBytes,
    orphanRisk: {
      // 单份对象：迁移后引用表恰 1 条引用；扫描与复制之间的删除会留下无引用对象。
      singleCopyObjects: groups.filter((g) => g.copies.length === 1).length,
      // 只被一个会话目录引用的对象：该目录被清理 ⇒ 对象成孤儿（回收窗靠引用表对账）。
      singleSessionObjects: groups.filter((g) => new Set(g.copies.map((c) => c.rel.split(sep)[0])).size === 1).length,
      // 同一 `会话目录/文件名` 对应 ≥2 种字节：回读反查若只按旧路径键会撞键。
      samePathDifferentBytes,
      readErrors: errors.length,
    },
    plan: {
      targetLayout: 'objects/<sha256[:2]>/<sha256> + refs (sessionDir/filename → sha256)',
      steps: [
        { id: 1, name: '只读建库 + 复制不删', writes: `${uniqueObjects} 个对象（${uniqueBytes} B）+ 引用表`, removes: '无' },
        { id: 2, name: '双写期', writes: '新写入同时落旧路径与新对象树（读仍走旧路径）', removes: '无' },
        { id: 3, name: '切读路径', writes: '读改走 objects/<h2>/<sha256>（旧路径保留只回读）', removes: '无' },
        { id: 4, name: '观察窗后回收', writes: '观察窗（建议 ≥ 14 天）后回收旧路径副本', removes: `${savedBytes} B（约 ${dupFiles - dupGroups.length} 个冗余副本）` },
      ],
      rollback: '任一步骤可回滚：旧路径在步骤④之前从未被删除 ⇒ 回滚 = 读路径切回旧路径，零数据丢失。',
      rollbackCost: `回滚不产生数据搬运；观察窗内磁盘占用峰值 = ${totalBytes + uniqueBytes} B（旧树 + 新对象树）。`,
      commandListForRoot: [
        '# ① 只读建库 + 复制不删（root 批准后手工执行；本批**未**执行）',
        'node scripts/nf-object-dedup-dryrun.mjs --root "$STAND_DATA_ROOT/uploads" --out /tmp/dedup-plan.json',
        '#（执行器 scripts/nf-object-dedup-apply.py 尚未编写：本批只做方案 + dry-run，执行器随批准另立项）',
        'df -h "$STAND_DATA_ROOT"   # 峰值核算：旧树 + 新对象树',
        '# ② 双写期（改写入腿：AttachmentCommitService + WebSocketRoutes 上传腿；读路径不动）',
        '# ③ 切读路径（uploadsServe 先查引用表 → objects/；旧路径仍在）',
        '# ④ 观察窗后回收（默认关闭；需独立批准，先 --mode verify 对账零孤儿再 --mode collect）',
      ],
    },
    topGroups: groups.slice(0, 40).map((g) => ({
      sha256: g.sha256, bytes: g.bytes, copies: g.copies.length,
      rels: g.copies.slice(0, 12).map((c) => c.rel),
      wastedBytes: g.bytes * (g.copies.length - 1),
    })),
    errors: errors.slice(0, 50),
  };
}

const args = parseArgs(process.argv.slice(2));
const roots = [resolve(args.root), ...args.extraRoots.map(resolve)];
const perRoot = [];
for (const r of roots) {
  try { statSync(r); } catch { perRoot.push({ root: r, missing: true }); continue; }
  perRoot.push(await scan(r));
}
const combined = {
  batch: 'media-object-cid-20261118',
  kind: 'storage-object-dedup-dry-run',
  readOnly: true,
  generatedAt: new Date().toISOString(),
  roots: perRoot,
  totals: perRoot.filter((r) => !r.missing).reduce((acc, r) => ({
    files: acc.files + r.files,
    totalBytes: acc.totalBytes + r.totalBytes,
    uniqueObjects: acc.uniqueObjects + r.uniqueObjects,
    uniqueBytes: acc.uniqueBytes + r.uniqueBytes,
    duplicateGroups: acc.duplicateGroups + r.duplicateGroups,
    savedBytes: acc.savedBytes + r.savedBytes,
    peakBytesEstimate: acc.peakBytesEstimate + r.peakBytesEstimate,
  }), { files: 0, totalBytes: 0, uniqueObjects: 0, uniqueBytes: 0, duplicateGroups: 0, savedBytes: 0, peakBytesEstimate: 0 }),
};
const text = JSON.stringify(combined, null, 2);
if (args.out) writeFileSync(args.out, text + '\n');
else console.log(text);
if (args.out) {
  const t = combined.totals;
  console.log(`[dry-run] roots=${roots.length} files=${t.files} bytes=${t.totalBytes} ` +
    `objects=${t.uniqueObjects} uniqueBytes=${t.uniqueBytes} dupGroups=${t.duplicateGroups} ` +
    `savedBytes=${t.savedBytes} peak=${t.peakBytesEstimate}`);
  console.log('[dry-run] read-only: no file under the scanned roots was created, moved or deleted.');
}
