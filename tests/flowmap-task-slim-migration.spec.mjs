#!/usr/bin/env node
// flowmap-task-slim-migration.spec.mjs — Flow Map 存储瘦身批（2026-09-06）B2 fixture 单测。
//
// 被测对象：scripts/migrate-flowmap-task-slim.mjs（存量清洗脚本，本批只进仓+自证，
// **不对 ~/.nebflow/projects/* 实跑**——红线 B3，执行窗口排 restart #2 之后）。
//
// A/B 硬契约断言面（与 FlowMapStore.writeTaskFiles / hydrateAndMigrate /
// stripNodeTasks / summarizeTask 严格一致）：
//   1. 活动区：task 全文挪 tasks/<id>.md（字节 = 原文零加工、缺失才写）+ JSON task
//      收敛 ≤500 字符摘要 + taskFile 指针；description / result / resultFile 零触碰；
//   2. 归档区：task / taskFile 两键直接剥，不落 per-node 文件；
//   3. 幂等：重跑零变更（字节级不变、备份不覆盖）；
//   4. --dry-run：统计照出、磁盘零写入（无 tasks/ 目录、无 .bak、JSON 原样）；
//   5. 备份：首次有效变更写 <file>.task-slim.bak（含清洗前全文形态）。
//
// Run: node tests/flowmap-task-slim-migration.spec.mjs   （node 直跑，非零退出码 = 红）

import { strict as assert } from 'node:assert';
import {
  mkdtempSync, rmSync, mkdirSync, writeFileSync, readFileSync,
  existsSync, readdirSync, statSync,
} from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import {
  summarizeTask, TASK_SUMMARY_CAP,
  slimActiveRecord, stripArchiveRecord,
  migrateProjectDir, collectProjectDirs,
} from '../scripts/migrate-flowmap-task-slim.mjs';

const LONG_TASK = '任务段落五个字。'.repeat(120) + 'END-MARKER-TASK-FULL'; // > 500 chars
assert.ok(LONG_TASK.length > TASK_SUMMARY_CAP, 'fixture long task must exceed cap');

function fixtureRoot(tag) {
  const root = mkdtempSync(join(tmpdir(), `nb-task-slim-${tag}-`));
  return root;
}

function writeJson(file, obj) {
  mkdirSync(join(file, '..'), { recursive: true });
  writeFileSync(file, JSON.stringify(obj));
}

function makeProject(root, name, activeNodes, archiveNodes) {
  const nb = join(root, name, '.nebflow');
  mkdirSync(nb, { recursive: true });
  if (activeNodes) writeJson(join(nb, 'flow-map.json'), { v: 1, project: name, updatedAt: 1728000000000, nodes: activeNodes });
  if (archiveNodes) writeJson(join(nb, 'flow-map-archive.json'), { project: name, nodes: archiveNodes });
  return nb;
}

const results = [];
function test(name, fn) {
  try {
    fn();
    results.push(['PASS', name, null]);
  } catch (e) {
    results.push(['FAIL', name, e]);
  }
}

// ── 0. 摘要规则单点（= FlowMapStore.summarizeTask 逐字节同规则）──

test('summarizeTask: >cap → slice+…; ≤cap → identity; boundary 500 stays full', () => {
  assert.equal(summarizeTask('x'.repeat(501)), 'x'.repeat(500) + '…');
  assert.equal(summarizeTask('x'.repeat(500)), 'x'.repeat(500));
  assert.equal(summarizeTask('短任务'), '短任务');
  assert.equal(summarizeTask('x'.repeat(501)).length, TASK_SUMMARY_CAP + 1);
});

// ── 1. 活动区清洗：挪文件 + 摘要 + 指针 + 旁键零触碰 ──────────────

test('active slim: task full text moved to tasks/<id>.md, JSON carries summary + taskFile pointer, other keys untouched', () => {
  const root = fixtureRoot('active');
  try {
    const nb = makeProject(root, 'alpha', {
      'n-long': { id: 'n-long', name: 'long', agent: 'general', description: '拆分验收节点', task: LONG_TASK, status: 'pending', in: [], out: 'Nebula' },
      'n-short': { id: 'n-short', name: 'short', agent: 'general', task: '短任务原文', status: 'wiring' },
      'n-notask': { id: 'n-notask', name: 'nt', agent: 'general', result: '短结果', resultFile: 'results/n-notask.md', status: 'completed' },
    }, null);
    const s = migrateProjectDir(nb, { dryRun: false });
    assert.equal(s.activeStripped, 2, 'two active records carry task');
    assert.equal(s.moved, 2);
    assert.equal(s.archiveStripped, 0);
    assert.ok(s.bytesAfter < s.bytesBefore, 'JSON must shrink');

    const after = JSON.parse(readFileSync(join(nb, 'flow-map.json'), 'utf8'));
    // n-long：摘要 + 指针；description 等字段逐项保留
    const recLong = after.nodes['n-long'];
    assert.equal(recLong.task, summarizeTask(LONG_TASK));
    assert.equal(recLong.task.length, TASK_SUMMARY_CAP + 1);
    assert.ok(recLong.task.endsWith('…'));
    assert.equal(recLong.taskFile, 'tasks/n-long.md');
    assert.equal(recLong.description, '拆分验收节点', 'description preserved verbatim');
    assert.equal(recLong.out, 'Nebula');
    assert.equal(recLong.status, 'pending');
    assert.equal(recLong.in.length, 0);
    // per-node 文件：字节 = 原文零加工
    assert.equal(readFileSync(join(nb, 'tasks', 'n-long.md'), 'utf8'), LONG_TASK);
    // n-short：短任务原样保留 + 指针 + 文件物化
    const recShort = after.nodes['n-short'];
    assert.equal(recShort.task, '短任务原文');
    assert.equal(recShort.taskFile, 'tasks/n-short.md');
    assert.equal(readFileSync(join(nb, 'tasks', 'n-short.md'), 'utf8'), '短任务原文');
    // n-notask：无 task → 记录零触碰（含既有 resultFile 指针）
    assert.deepEqual(after.nodes['n-notask'], { id: 'n-notask', name: 'nt', agent: 'general', result: '短结果', resultFile: 'results/n-notask.md', status: 'completed' });
    // tasks/ 目录只含挪移的两个文件
    assert.deepEqual(readdirSync(join(nb, 'tasks')).sort(), ['n-long.md', 'n-short.md']);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

// ── 2. 归档区清洗：task/taskFile 直接剥，不落文件 ─────────────────

test('archive strip: task/taskFile keys removed, no per-node file, siblings intact', () => {
  const root = fixtureRoot('archive');
  try {
    const nb = makeProject(root, 'beta', null, {
      'n-a1': { id: 'n-a1', name: 'a1', agent: 'general', task: LONG_TASK, taskFile: 'tasks/n-a1.md', result: '归档结果', resultFile: 'results/n-a1.md', status: 'completed' },
      'n-a2': { id: 'n-a2', name: 'a2', agent: 'general', task: '短任务', status: 'blocked' },
      'n-a3': { id: 'n-a3', name: 'a3', agent: 'general', result: 'r3', status: 'cancelled' },
    });
    const s = migrateProjectDir(nb, { dryRun: false });
    assert.equal(s.archiveStripped, 2);
    assert.equal(s.moved, 0, 'archive nodes never move task to file');
    assert.equal(s.activeStripped, 0);

    const after = JSON.parse(readFileSync(join(nb, 'flow-map-archive.json'), 'utf8'));
    assert.equal('task' in after.nodes['n-a1'], false, 'task key stripped');
    assert.equal('taskFile' in after.nodes['n-a1'], false, 'stale taskFile pointer stripped too');
    assert.equal(after.nodes['n-a1'].result, '归档结果', 'result untouched');
    assert.equal(after.nodes['n-a1'].resultFile, 'results/n-a1.md', 'resultFile untouched');
    assert.equal('task' in after.nodes['n-a2'], false);
    assert.equal(after.nodes['n-a2'].status, 'blocked');
    assert.deepEqual(after.nodes['n-a3'], { id: 'n-a3', name: 'a3', agent: 'general', result: 'r3', status: 'cancelled' });
    assert.equal(existsSync(join(nb, 'tasks')), false, 'no tasks/ dir for archive-only strip');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

// ── 3. 幂等：重跑零变更（字节级），备份不覆盖 ─────────────────────

test('idempotent: second run is zero-change, .task-slim.bak never overwritten', () => {
  const root = fixtureRoot('idem');
  try {
    const nb = makeProject(root, 'gamma', {
      'n-1': { id: 'n-1', name: 'one', agent: 'general', task: LONG_TASK, status: 'pending' },
    }, {
      'n-2': { id: 'n-2', name: 'two', agent: 'general', task: LONG_TASK, status: 'completed' },
    });
    const s1 = migrateProjectDir(nb, { dryRun: false });
    assert.ok(s1.files.every((f) => f.changed));
    const activeAfter1 = readFileSync(join(nb, 'flow-map.json'), 'utf8');
    const archiveAfter1 = readFileSync(join(nb, 'flow-map-archive.json'), 'utf8');
    const bakActive = readFileSync(join(nb, 'flow-map.json.task-slim.bak'), 'utf8');
    const bakArchive = readFileSync(join(nb, 'flow-map-archive.json.task-slim.bak'), 'utf8');
    assert.ok(bakActive.includes(LONG_TASK), 'active backup must hold pre-migration full-text form');
    assert.ok(bakArchive.includes(LONG_TASK));
    const bakMtime = statSync(join(nb, 'flow-map.json.task-slim.bak')).mtimeMs;

    const s2 = migrateProjectDir(nb, { dryRun: false });
    assert.equal(s2.activeStripped, 0, 'second run: no active records to strip');
    assert.equal(s2.archiveStripped, 0);
    assert.equal(s2.moved, 0);
    assert.equal(s2.bytesBefore, 0, 'second run: zero bytes rewritten');
    assert.equal(readFileSync(join(nb, 'flow-map.json'), 'utf8'), activeAfter1, 'active JSON byte-identical');
    assert.equal(readFileSync(join(nb, 'flow-map-archive.json'), 'utf8'), archiveAfter1, 'archive JSON byte-identical');
    assert.equal(statSync(join(nb, 'flow-map.json.task-slim.bak')).mtimeMs, bakMtime, 'backup mtime untouched');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

// ── 4. --dry-run：统计照出、磁盘零写入 ───────────────────────────

test('dry-run: stats reported but zero writes (no tasks dir, no .bak, JSON untouched)', () => {
  const root = fixtureRoot('dry');
  try {
    const nb = makeProject(root, 'delta', {
      'n-1': { id: 'n-1', name: 'one', agent: 'general', task: LONG_TASK, status: 'pending' },
    }, {
      'n-2': { id: 'n-2', name: 'two', agent: 'general', task: LONG_TASK, status: 'completed' },
    });
    const activeBefore = readFileSync(join(nb, 'flow-map.json'), 'utf8');
    const archiveBefore = readFileSync(join(nb, 'flow-map-archive.json'), 'utf8');
    const s = migrateProjectDir(nb, { dryRun: true });
    assert.equal(s.activeStripped, 1, 'plan still counts active strip');
    assert.equal(s.archiveStripped, 1);
    assert.equal(s.moved, 1);
    assert.ok(s.bytesAfter < s.bytesBefore, 'plan still reports projected shrink');
    assert.equal(readFileSync(join(nb, 'flow-map.json'), 'utf8'), activeBefore, 'active JSON untouched');
    assert.equal(readFileSync(join(nb, 'flow-map-archive.json'), 'utf8'), archiveBefore, 'archive JSON untouched');
    assert.equal(existsSync(join(nb, 'tasks')), false, 'no tasks dir in dry-run');
    assert.equal(existsSync(join(nb, 'flow-map.json.task-slim.bak')), false, 'no backup in dry-run');
    // dry-run 之后实跑仍能正常收敛（plan→apply 通路）
    const s2 = migrateProjectDir(nb, { dryRun: false });
    assert.equal(s2.activeStripped, 1);
    assert.equal(readFileSync(join(nb, 'tasks', 'n-1.md'), 'utf8'), LONG_TASK);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

// ── 5. 纯函数 + 扫描器边角 ────────────────────────────────────────

test('edge: whitespace-only task untouched; unsafe id skipped; missing nodes key no-op; collectProjectDirs sorts', () => {
  const r1 = slimActiveRecord({ id: 'n-x', task: '   \n  ' });
  assert.equal(r1.stripped, false, 'whitespace-only task is not a real task');
  const r2 = slimActiveRecord({ id: '../evil', task: LONG_TASK });
  assert.equal(r2.stripped, false, 'unsafe id refused');
  const r3 = stripArchiveRecord({ id: 'n-y' });
  assert.equal(r3.stripped, false, 'archive record without task untouched');

  const root = fixtureRoot('scan');
  try {
    makeProject(root, 'b-proj', { 'n-1': { id: 'n-1', name: 'x', agent: 'g' } }, null);
    mkdirSync(join(root, 'empty-proj'), { recursive: true }); // 无 .nebflow → 不入列
    assert.deepEqual(collectProjectDirs(root), ['b-proj']);
    // 无 nodes 键的 flow-map.json → no-op 不炸
    const nb = join(root, 'b-proj', '.nebflow');
    writeJson(join(nb, 'flow-map.json'), { v: 1, project: 'b-proj' });
    const s = migrateProjectDir(nb, { dryRun: false });
    assert.equal(s.activeStripped, 0);
    assert.equal(s.errors.length, 0);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

// ── 汇总 ─────────────────────────────────────────────────────────

let failed = 0;
for (const [status, name, err] of results) {
  console.log(`${status}  ${name}`);
  if (status === 'FAIL') {
    failed += 1;
    console.log(err && err.stack ? err.stack : String(err));
  }
}
console.log('─'.repeat(72));
console.log(`${results.length - failed}/${results.length} passed`);
process.exit(failed > 0 ? 1 : 0);
