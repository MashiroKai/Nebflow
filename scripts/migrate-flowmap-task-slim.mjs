#!/usr/bin/env node
// migrate-flowmap-task-slim.mjs — Flow Map 存储瘦身批（2026-09-06）一次性存量清洗：
// 扫 <root>/<project>/.nebflow/flow-map.json（活动区）与 flow-map-archive.json（归档区），
// 把节点记录里的 task 全文剥出 JSON——
//   - 活动节点（有重入价值）→ 挪 per-node 文件 `.nebflow/tasks/<nodeId>.md`（A/B 硬契约：
//     与 FlowMapStore.writeTaskFiles / hydrateAndMigrate 同路径同字节——文件缺失才写、
//     内容 = task 原文零加工；JSON 内 task 收敛为 ≤500 字符摘要 + `taskFile` 指针，
//     摘要规则与 FlowMapStore.summarizeTask 逐字节一致）；
//   - 归档节点（无重入价值）→ 直接剥 task / taskFile 两键（不落文件，对齐
//     FlowMapStore.stripNodeTasks）；
//   - 其余字段（description / result / resultFile / 元数据）零触碰。
//
// ⚠️ 红线（20260906 机制批 B3）：本脚本**不在构建批实跑**——执行窗口排在 restart #2
// 之后（~/.nebflow/projects/* 沙箱写根解锁）。届时先 `--dry-run` 报作者过目，再
// `--yes` 实跑。无 --yes 的实跑会被拒绝（防误触发）。
//
// 幂等：重跑零变更（已收敛记录不再产生写——文件存在跳过挪移、摘要幂等、指针已正确
// 跳过补、备份不覆盖）。首次有效变更前写 `<file>.task-slim.bak`（不覆盖既有备份，
// 回滚锚点恒为首次清洗前状态；与 store 侧 flow-map.json.bak 锚点互不踩踏）。
// 原子写：tmp + rename（对齐仓内 AtomicJson 语义）。
//
// Run:
//   node scripts/migrate-flowmap-task-slim.mjs --dry-run          # 计划 + 变更统计，零写入
//   node scripts/migrate-flowmap-task-slim.mjs --yes              # 实跑（写文件/改 JSON/备份）
//   node scripts/migrate-flowmap-task-slim.mjs --dry-run --root /tmp/fixture-projs
//
// Env/args:
//   --root <dir>   项目根（默认 ~/.nebflow/projects）
//   --dry-run      只算不写
//   --yes          实跑确认（缺省拒绝写入并退出码 2）

import {
  existsSync, readFileSync, writeFileSync, mkdirSync, renameSync,
  copyFileSync, readdirSync, rmSync,
} from 'node:fs';
import { join, resolve, dirname, basename } from 'node:path';
import { homedir } from 'node:os';
import { randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';

/** per-node task 文件目录名（= FlowMapStore.TasksDirName）。 */
export const TASKS_DIR_NAME = 'tasks';
/** 落盘 JSON 内 task 摘要截断上限（= FlowMapStore.TaskSummaryCap）。 */
export const TASK_SUMMARY_CAP = 500;

/** task 摘要规则单点（= FlowMapStore.summarizeTask：>Cap slice(0,Cap)+"…"）。 */
export function summarizeTask(t) {
  return t.length > TASK_SUMMARY_CAP ? t.slice(0, TASK_SUMMARY_CAP) + '…' : t;
}

const isRealTask = (v) => typeof v === 'string' && v.trim() !== '';
const safeId = (id) => typeof id === 'string' && id.length > 0 && !id.includes('/') && !id.includes('\\') && id !== '.' && id !== '..';

/** 原子写文本文件：tmp + rename（并发/崩溃安全，对齐仓内 AtomicJson 语义）。 */
function atomicWrite(file, content) {
  const tmp = join(dirname(file), `.${basename(file)}.tmp.${randomUUID()}`);
  writeFileSync(tmp, content);
  try {
    renameSync(tmp, file);
  } catch (e) {
    try { rmSync(tmp, { force: true }); } catch { /* best-effort cleanup */ }
    throw e;
  }
}

/**
 * 活动区清洗：剥 task 全文 → tasks/<id>.md（缺失才写）+ JSON 摘要 + taskFile 指针。
 * 纯函数（不动磁盘）——文件挪移由调用方按返回的 movePlan 执行，保证 dry-run 零副作用。
 */
export function slimActiveRecord(rec) {
  if (!isRealTask(rec.task)) return { rec, moved: false, stripped: false };
  const id = rec.id;
  if (!safeId(id)) return { rec, moved: false, stripped: false, skipped: 'unsafe-id' };
  const summarized = summarizeTask(rec.task);
  const pointer = `${TASKS_DIR_NAME}/${id}.md`;
  const taskChanged = rec.task !== summarized;
  const pointerChanged = rec.taskFile !== pointer;
  if (!taskChanged && !pointerChanged) return { rec, moved: false, stripped: false };
  return {
    rec: { ...rec, task: summarized, taskFile: pointer },
    moved: true, // JSON 侧含 task 全文 ⇒ 需要确保 per-node 文件存在
    stripped: true,
  };
}

/** 归档区清洗：task / taskFile 两键直接剥（纯函数）。 */
export function stripArchiveRecord(rec) {
  if (!('task' in rec) && !('taskFile' in rec)) return { rec, stripped: false };
  const { task, taskFile, ...rest } = rec;
  return { rec: rest, stripped: true };
}

/**
 * 清洗单个项目的 .nebflow 目录（flow-map.json 活动区 + flow-map-archive.json 归档区）。
 * @returns stats = { project, files: [{file, changed, moved, stripped, bytesBefore, bytesAfter}], errors }
 */
export function migrateProjectDir(nbDir, { dryRun = false } = {}) {
  const stats = {
    project: nbDir.split('/').filter(Boolean).slice(-2, -1)[0] ?? nbDir, // <root>/<project>/.nebflow → project
    files: [], errors: [],
    moved: 0, activeStripped: 0, archiveStripped: 0,
    bytesBefore: 0, bytesAfter: 0,
  };

  const handle = (fileName, mode) => {
    const file = join(nbDir, fileName);
    if (!existsSync(file)) return;
    let raw;
    try { raw = readFileSync(file, 'utf8'); } catch (e) { stats.errors.push(`${fileName}: read failed: ${e.message}`); return; }
    let doc;
    try { doc = JSON.parse(raw); } catch (e) { stats.errors.push(`${fileName}: invalid JSON: ${e.message}`); return; }
    if (!doc || typeof doc !== 'object' || !doc.nodes || typeof doc.nodes !== 'object') return;

    const moves = []; // { id, content } 需确保存在的 per-node 文件
    let changed = false;
    const nodes = {};
    for (const [id, rec] of Object.entries(doc.nodes)) {
      if (!rec || typeof rec !== 'object') { nodes[id] = rec; continue; }
      if (mode === 'active') {
        const r = slimActiveRecord(rec);
        nodes[id] = r.rec;
        if (r.stripped) {
          changed = true;
          stats.activeStripped += 1;
          if (r.moved && safeId(id)) moves.push({ id, content: rec.task });
        }
      } else {
        const r = stripArchiveRecord(rec);
        nodes[id] = r.rec;
        if (r.stripped) { changed = true; stats.archiveStripped += 1; }
      }
    }
    if (!changed) { stats.files.push({ file: fileName, changed: false, bytesBefore: raw.length, bytesAfter: raw.length }); return; }

    const after = JSON.stringify({ ...doc, nodes });
    stats.files.push({ file: fileName, changed: true, moved: moves.length, bytesBefore: raw.length, bytesAfter: after.length });
    stats.moved += moves.length;
    stats.bytesBefore += raw.length;
    stats.bytesAfter += after.length;
    if (dryRun) return;

    // 首次有效变更前备份（不覆盖既有备份）
    const bak = `${file}.task-slim.bak`;
    if (!existsSync(bak)) copyFileSync(file, bak);
    // per-node 文件：缺失才写（与 store 水合挪移同规则——不覆盖可能更新的文件内容）
    if (moves.length > 0) {
      const tasksDir = join(nbDir, TASKS_DIR_NAME);
      mkdirSync(tasksDir, { recursive: true });
      for (const m of moves) {
        const p = join(tasksDir, `${m.id}.md`);
        if (!existsSync(p)) writeFileSync(p, m.content);
      }
    }
    atomicWrite(file, after);
  };

  handle('flow-map.json', 'active');
  handle('flow-map-archive.json', 'archive');
  return stats;
}

/** 枚举 root 下含 .nebflow/flow-map*.json 的项目目录名。 */
export function collectProjectDirs(root) {
  if (!existsSync(root)) return [];
  return readdirSync(root, { withFileTypes: true })
    .filter((d) => d.isDirectory() || d.isSymbolicLink())
    .map((d) => d.name)
    .filter((name) => existsSync(join(root, name, '.nebflow')))
    .sort();
}

function fmtBytes(n) {
  if (n < 1024) return `${n}B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}KB`;
  return `${(n / 1024 / 1024).toFixed(2)}MB`;
}

function main() {
  const argv = process.argv.slice(2);
  const dryRun = argv.includes('--dry-run');
  const apply = argv.includes('--yes');
  const rootIdx = argv.indexOf('--root');
  const root = rootIdx >= 0 ? resolve(argv[rootIdx + 1]) : join(homedir(), '.nebflow', 'projects');

  if (!dryRun && !apply) {
    console.error('REFUSED  实跑需显式 --yes（红线 B3：先 --dry-run 报作者过目）。');
    console.error('Usage: node scripts/migrate-flowmap-task-slim.mjs --dry-run | --yes [--root <dir>]');
    process.exit(2);
  }

  const projects = collectProjectDirs(root);
  if (projects.length === 0) {
    console.log(`no projects with .nebflow/ under ${root} — nothing to do`);
    return;
  }
  console.log(`${dryRun ? 'DRY-RUN' : 'APPLY '}  root=${root}  projects=${projects.length}`);

  let totMoved = 0, totActive = 0, totArchive = 0, totBefore = 0, totAfter = 0, totErrors = 0;
  for (const name of projects) {
    const s = migrateProjectDir(join(root, name, '.nebflow'), { dryRun });
    const ch = s.files.filter((f) => f.changed);
    totMoved += s.moved; totActive += s.activeStripped; totArchive += s.archiveStripped;
    totBefore += s.bytesBefore; totAfter += s.bytesAfter; totErrors += s.errors.length;
    const line = [
      `${dryRun ? '[plan]' : '[done]'} ${name}:`,
      `activeStripped=${s.activeStripped}`, `archiveStripped=${s.archiveStripped}`, `movedFiles=${s.moved}`,
      ch.length > 0 ? `bytes ${fmtBytes(s.bytesBefore)} → ${fmtBytes(s.bytesAfter)}` : 'no-change',
    ].join('  ');
    console.log(line);
    for (const e of s.errors) console.log(`  ERROR ${e}`);
    for (const f of s.files.filter((x) => x.changed)) console.log(`  changed: ${f.file}`);
  }

  console.log('─'.repeat(72));
  console.log(
    `TOTAL  activeStripped=${totActive}  archiveStripped=${totArchive}  movedFiles=${totMoved}  ` +
    `bytes ${fmtBytes(totBefore)} → ${fmtBytes(totAfter)}  errors=${totErrors}`,
  );
  if (totErrors > 0) process.exit(1);
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (isMain) main();
