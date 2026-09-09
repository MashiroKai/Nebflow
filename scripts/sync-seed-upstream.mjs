#!/usr/bin/env node
// sync-seed-upstream.mjs — 种子插件上游跟随（check / apply 两模式）。
//
// 背景：src/main/resources/seed/plugins/<name>/ 是蒸馏管线产物——上游 skill
// 仓库整体随迁进种子（如 slideblocks = github.com/UniUni2000/slideblocks-skill
// 的 skills/slideblocks 逐字镜像 + 同插件内 Nebflow 特有蒸馏 skills + Nebflow
// 定制 plugin.json）。上游演进后需人工重跑蒸馏，本脚本把「检测 + 机械镜像」
// 自动化，蒸馏判断与信任门 re-approve 留给人（§ 信任门纪律）。
//
// 用法（仓库根执行）：
//   node scripts/sync-seed-upstream.mjs            # check：报告差异，不改文件
//   node scripts/sync-seed-upstream.mjs --apply    # apply：镜像同步 + 推进基线
//
// 对每个 scripts/seed-upstream.json 里登记的插件：
//   1. git clone 上游仓库到临时目录（临时目录退出时清理）
//   2. 字节级对比 upstreamDir(HEAD) ↔ seedDir（双向：缺失/多余/内容变更）
//   3. check：打印差异清单 + 上游侧 baseline..HEAD 的变更文件（git diff）
//      apply：把 upstreamDir 镜像进 seedDir（只动该子目录，不碰 plugin.json
//      与同插件内 Nebflow 特有 skills），推进基线 commit
//
// 信任门纪律（digest 变更时顺序不可倒）：
//   同步 → sbt assembly + 隔离 fresh home 冒烟验证 → plugin re-approve。
//   同步窗口内其他项目新节点配该插件会被 PLUGIN_UNTRUSTED 拒——预期行为。

import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, readdirSync, statSync, readFileSync, writeFileSync, existsSync, mkdirSync, copyFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const APPLY = process.argv.includes('--apply');
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TRACKING = join(ROOT, 'scripts', 'seed-upstream.json');

const tracking = JSON.parse(readFileSync(TRACKING, 'utf8'));
const names = Object.keys(tracking.plugins ?? {});
if (names.length === 0) {
  console.log('[OK] seed-upstream.json 未登记任何插件——无事可做');
  process.exit(0);
}

const git = (args, opts = {}) => execFileSync('git', args, { encoding: 'utf8', ...opts }).trim();
const today = () => new Date().toISOString().slice(0, 10);

// ── 递归文件清单（相对路径 → 绝对路径）─────────────────────────────────
function walk(dir) {
  const out = new Map();
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) for (const [rel, abs] of walk(p)) out.set(join(name, rel), abs);
    else out.set(name, p);
  }
  return out;
}

// ── 对比：返回 {added, removed, changed}（视角：upstream 为基准）────────
function diffTrees(upMap, seedMap) {
  const added = [...upMap.keys()].filter((k) => !seedMap.has(k)).sort();
  const removed = [...seedMap.keys()].filter((k) => !upMap.has(k)).sort();
  const changed = [...upMap.keys()]
    .filter((k) => seedMap.has(k) && !readFileSync(upMap.get(k)).equals(readFileSync(seedMap.get(k))))
    .sort();
  return { added, removed, changed };
}

// ── 镜像 upstreamDir → seedDir（双向，含删除多余文件）──────────────────
function mirror(upDir, seedDir) {
  const upMap = walk(upDir);
  const seedMap = existsSync(seedDir) ? walk(seedDir) : new Map();
  mkdirSync(seedDir, { recursive: true });
  let n = 0;
  for (const [rel, abs] of upMap) {
    const target = join(seedDir, rel);
    mkdirSync(dirname(target), { recursive: true });
    if (!seedMap.has(rel) || !readFileSync(abs).equals(readFileSync(target))) {
      copyFileSync(abs, target);
      n++;
    }
  }
  let d = 0;
  for (const rel of seedMap.keys()) {
    if (!upMap.has(rel)) {
      rmSync(join(seedDir, rel));
      d++;
    }
  }
  return { written: n, removed: d };
}

let exitCode = 0;
for (const name of names) {
  const e = tracking.plugins[name];
  console.log(`\n== ${name} ==  ${e.upstreamRepo}`);
  console.log(`   recorded baseline: ${e.baseline} (${e.recordedAt || 'n/a'})`);

  const tmp = mkdtempSync(join(tmpdir(), 'seed-upstream-'));
  try {
    // 1. clone + HEAD
    try {
      git(['clone', '--quiet', e.upstreamRepo, tmp]);
    } catch (err) {
      console.log(`[FAIL] git clone 失败——检查网络/仓库可达性: ${err.message.split('\n')[0]}`);
      exitCode = 1;
      continue;
    }
    const head = git(['rev-parse', 'HEAD'], { cwd: tmp });
    const headShort = head.slice(0, 7);
    console.log(`   upstream HEAD: ${headShort} — ${git(['log', '-1', '--format=%s'], { cwd: tmp })}`);

    // 2. 字节级对比
    const upDir = join(tmp, ...e.upstreamDir.split('/'));
    const seedDir = join(ROOT, ...e.seedDir.split('/'));
    if (!existsSync(upDir)) {
      console.log(`[FAIL] 上游仓库不存在 ${e.upstreamDir}——仓库结构变更，需人工重定蒸馏映射`);
      exitCode = 1;
      continue;
    }
    const d = diffTrees(walk(upDir), existsSync(seedDir) ? walk(seedDir) : new Map());
    const dirty = d.added.length + d.removed.length + d.changed.length > 0;

    // 3a. 上游侧基线以来动了什么（供报告）
    let upstreamMoved = head !== e.baselineFull;
    let upstreamSideChanges = [];
    if (upstreamMoved) {
      try {
        upstreamSideChanges = git(['diff', '--name-status', e.baseline, 'HEAD', '--', e.upstreamDir], { cwd: tmp })
          .split('\n').filter(Boolean);
      } catch { /* baseline 不在上游历史（被 rebase 掉）→ 视为需人工核对 */ }
    }

    if (!dirty) {
      console.log(`[OK] 种子已是上游最新（byte-identical @ ${headShort}）`);
      if (upstreamMoved && upstreamSideChanges.length === 0)
        console.log(`     （上游自基线 ${e.baseline} 有新提交，但均未触及 ${e.upstreamDir}——基线可推进）`);
      if (upstreamSideChanges.length > 0)
        console.log(`     （注意：上游 ${e.baseline}..${headShort} 触及蒸馏目录但种子未变——人工核对基线一致性）`);
      if (APPLY || head !== e.upstreamHeadAtCheck) {
        e.upstreamHeadAtCheck = head;
        e.recordedAt = today();
        if (APPLY) { e.baseline = headShort; e.baselineFull = head; }
        writeFileSync(TRACKING, JSON.stringify(tracking, null, 2) + '\n');
        console.log(`     tracking ${APPLY ? 'baseline → ' + headShort : 'head-check'} 已更新`);
      }
      continue;
    }

    // 3b. 有差异：报告（两种模式都报；--apply 才动文件）
    exitCode = 1;
    const show = (label, list) => { if (list.length) console.log(`   ${label}:\n     ${list.join('\n     ')}`); };
    console.log(`[NEEDS SYNC] seed 落后/偏离上游（upstream HEAD ${headShort}）`);
    show('上游新增', d.added); show('上游删除', d.removed); show('内容变更', d.changed);
    if (upstreamSideChanges.length)
      console.log(`   上游侧变更（${e.baseline}..${headShort}）:\n     ${upstreamSideChanges.join('\n     ')}`);

    if (!APPLY) {
      console.log(`   → 复核后执行: node scripts/sync-seed-upstream.mjs --apply`);
      continue;
    }
    const r = mirror(upDir, seedDir);
    e.baseline = headShort;
    e.baselineFull = head;
    e.upstreamHeadAtCheck = head;
    e.recordedAt = today();
    writeFileSync(TRACKING, JSON.stringify(tracking, null, 2) + '\n');
    exitCode = 0; // apply 成功 ≠ 异常；验证门由信任门纪律（下方提醒）承载
    console.log(`[APPLIED] 镜像完成：写入 ${r.written} 个文件，删除 ${r.removed} 个多余文件；baseline → ${headShort}`);
    console.log('   ⚠ 信任门纪律（顺序不可倒）：');
    console.log('     ① sbt assembly 重打包 → ② 隔离 fresh home 冒烟验证（scripts/ 冒烟配方）→');
    console.log('     ③ 验证全绿后再 plugin re-approve（digest 变更 = 升级即重审，§B.8-3）。');
    console.log('     plugin.json 为 Nebflow 定制（含同插件特有蒸馏 skills），本脚本不触碰——');
    console.log('     上游若有能力面变化需人工评估是否改写 description/capability/version。');
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
}

process.exit(exitCode);
