#!/usr/bin/env node
// check-ui-structure.mjs — 渐进式披露 · 单一来源无重复 静态哨兵
// （ui-design-requirements 批 2026-11-18；作者令逐字：
//  「然后底部的账户栏 功能重复 信息重复 这个要进入我们插件的前端设计要求 渐进式披露
//   好的设计应该是自解释的 并且不要有重复的功能和信息」。）
//
// Why a gate：作者点名的是**两件可复现的形态**，不是审美偏好：
//   · 「高级 高级」「关于 关于」—— 同一容器里同一个**文案键**占了两个标题槽；
//   · 「底部的账户栏 信息重复」—— 同一份**账号身份**在同一页面面里渲染了三次。
// 这两件此前只靠人看图发现（而且是在作者看的时候才被发现）。本脚本把它们变成常驻判据。
//
// 判据（数据面 = 规范文件的登记表，脚本只是执行器）：
//   ① 折叠预算 —— 顶层分组数 ≤ 表列「允许分组数」；默认展开项 ≤ 表列「允许默认展开数」
//      （规范：disclosure-dedup-spec.md 「## 2. 折叠预算表」）
//   ② 标题唯一 —— 同一容器内，同一文案键不得占 ≥2 个标题槽（槽 = `<h2>` 与 `title:`）
//      （规范：「### 3.2 机检登记表」表 1）
//   ③ 信息组渲染点 —— 同一信息组在同一文件内出现 ≥1 次的声明符号数 ≤ 允许渲染点
//      （规范：同节表 2）
//
// 覆盖边界（诚实声明，别把本闸当全覆盖）：②只覆盖登记在表 1 的**页面面**，③只覆盖登记在
// 表 2 的**信息组**。新面先登记进规范，再受约束；不登记 = 未受约束。
// 「同一可见文案同屏出现两次」是渲染事实，静态判不出 ⇒ 待建渲染腿（规范 §4）。
//
// 用法：
//   node scripts/check-ui-structure.mjs                  # 门禁（红/绿）
//   node scripts/check-ui-structure.mjs --json           # 机器读
//   node scripts/check-ui-structure.mjs --list           # 只列命中（不做基线比较）
//   node scripts/check-ui-structure.mjs --update-baseline # 重锚存量（只许在把命中降下来时用）
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, relative, isAbsolute } from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { resolveDesignSpec, specMissingMessage } from './lib/design-spec-source.mjs';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
/** 规范面（单源在 stand-dev 插件包内；本仓不再保留第二份拷贝）。 */
const SPEC_PATH = resolveDesignSpec('references/frontend/disclosure-dedup-spec.md');
/** 存量基线（本仓自己的判定面数据，随闸留在本仓）。 */
const BASELINE_PATH = join(ROOT, 'scripts', 'ui-structure-baseline.json');
const ARGS = new Set(process.argv.slice(2));

if (!SPEC_PATH) {
  console.error(specMissingMessage('references/frontend/disclosure-dedup-spec.md'));
  process.exit(2);
}

const specText = readFileSync(SPEC_PATH, 'utf8');

/** 切一个 `## X` / `### X` 节（到下一个同级或更高级标题为止）。 */
function section(spec, heading, stopHeads) {
  const idx = spec.indexOf(heading);
  if (idx < 0) return null;
  let body = spec.slice(idx + heading.length);
  for (const stop of stopHeads) {
    const at = body.indexOf(stop);
    if (at >= 0) body = body.slice(0, at);
  }
  return body;
}

/** Markdown 表行 → 单元格数组（非表行返回 null）。 */
function rowCells(line) {
  const t = line.trim();
  if (!t.startsWith('|')) return null;
  const cells = t.replace(/^\|/, '').replace(/\|$/, '').split('|').map((s) => s.trim());
  if (cells.some((c) => /^-{2,}$/.test(c.replace(/[:\s]/g, '')))) return null;
  return cells;
}

/** 仓库相对路径 → 绝对路径（表里写的是 `src/...` 形式）。 */
function absOf(relPath) {
  return isAbsolute(relPath) ? relPath : join(ROOT, relPath);
}

function readTarget(relPath) {
  const file = absOf(relPath.replace(/`/g, ''));
  return existsSync(file) ? { file, src: readFileSync(file, 'utf8') } : { file, src: null };
}

// ── ① 折叠预算表 ────────────────────────────────────────────────────────
function loadBudgets() {
  const body = section(specText, '## 2. 折叠预算表', ['\n## ']);
  if (body === null) return { ok: false, reason: '规范文件缺「## 2. 折叠预算表」节', rows: [] };
  const rows = [];
  for (const line of body.split('\n')) {
    const cells = rowCells(line);
    if (!cells || cells.length < 6) continue;
    if (/^判定面/.test(cells[0])) continue;
    const [page, file, attr, groups, symbol, open] = cells;
    if (!file || !attr || !symbol || !/^\d+$/.test(groups) || !/^\d+$/.test(open)) continue;
    rows.push({ page, file, attr, maxGroups: Number(groups), symbol, maxOpen: Number(open) });
  }
  if (!rows.length) return { ok: false, reason: '「## 2. 折叠预算表」表体为空或列不全（判定面|文件|分组属性|允许分组数|默认展开符号|允许默认展开数）', rows: [] };
  return { ok: true, rows };
}

/** 取 `symbol ... = { … }` 的对象字面量（平衡括号），用于数默认展开项。 */
function objectLiteralAfter(src, symbol) {
  const at = src.indexOf(symbol);
  if (at < 0) return null;
  const brace = src.indexOf('{', at);
  if (brace < 0) return null;
  let depth = 0;
  for (let i = brace; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) return src.slice(brace, i + 1);
    }
  }
  return null;
}

function checkBudgets(rows) {
  const hits = [];
  const notes = [];
  for (const row of rows) {
    const { file, src } = readTarget(row.file);
    if (src === null) {
      hits.push({ kind: 'budget', key: `budget::${row.page}::${row.file}::missing`, page: row.page, metric: '目标文件缺失', detail: `文件不存在：${row.file}` });
      continue;
    }
    const attrRe = new RegExp(`${row.attr}="([^"]+)"`, 'g');
    const groups = new Set();
    for (const m of src.matchAll(attrRe)) {
      if (/[$`{}]/.test(m[1])) continue; // 模板占位（如 "${name}" 的重排循环）不算一个分组
      groups.add(m[1]);
    }
    if (groups.size > row.maxGroups) {
      hits.push({
        kind: 'budget',
        key: `budget::${row.page}::${row.file}::groups`,
        page: row.page,
        metric: '顶层分组数',
        detail: `${groups.size} > 允许 ${row.maxGroups}（${[...groups].join(' / ')}）`,
      });
    } else {
      notes.push(`${row.page} 顶层分组 ${groups.size}/${row.maxGroups}`);
    }
    const obj = objectLiteralAfter(src, row.symbol);
    if (obj === null) {
      hits.push({ kind: 'budget', key: `budget::${row.page}::${row.file}::symbol`, page: row.page, metric: '默认展开符号', detail: `源码里找不到 ${row.symbol}` });
      continue;
    }
    const openCount = (obj.match(/\btrue\b/g) || []).length;
    if (openCount > row.maxOpen) {
      hits.push({
        kind: 'budget',
        key: `budget::${row.page}::${row.file}::open`,
        page: row.page,
        metric: '默认展开项',
        detail: `${openCount} > 允许 ${row.maxOpen}（新开该面必须只看摘要）`,
      });
    } else {
      notes.push(`${row.page} 默认展开 ${openCount}/${row.maxOpen}`);
    }
  }
  return { hits, notes };
}

// ── ② 标题唯一判定面（表 1）─────────────────────────────────────────────
function loadTitleFaces() {
  const body = section(specText, '表 1 · 标题唯一判定面', ['**表 2']);
  if (body === null) return { ok: false, reason: '规范缺「表 1 · 标题唯一判定面」', rows: [] };
  const rows = [];
  for (const line of body.split('\n')) {
    const cells = rowCells(line);
    if (!cells || cells.length < 4) continue;
    if (/^判定面/.test(cells[0])) continue;
    rows.push({ page: cells[0], file: cells[1], attr: cells[2], slots: cells[3].split(',').map((s) => s.trim()).filter(Boolean) });
  }
  if (!rows.length) return { ok: false, reason: '「表 1 · 标题唯一判定面」表体为空', rows: [] };
  return { ok: true, rows };
}

/**
 * 标题槽 → 文案键（槽形态写死在规范 §3.2 表 1 的「标题槽」列）：
 *   `<h2>`  → `<h2>${t('k')}</h2>`
 *   `title:` → `title: t('k')`
 */
function titleKeysIn(chunk, slots) {
  const out = [];
  if (slots.includes('<h2>')) {
    for (const m of chunk.matchAll(/<h2>\s*\$\{\s*t\(\s*'([^']+)'\s*\)/g)) out.push({ slot: '<h2>', key: m[1] });
  }
  if (slots.includes('title:')) {
    for (const m of chunk.matchAll(/title:\s*t\(\s*'([^']+)'\s*\)/g)) out.push({ slot: 'title:', key: m[1] });
  }
  return out;
}

function checkTitleUniqueness(rows) {
  const hits = [];
  const notes = [];
  for (const row of rows) {
    const { src } = readTarget(row.file);
    if (src === null) {
      hits.push({ kind: 'title', key: `title::${row.page}::${row.file}::missing`, page: row.page, detail: `文件不存在：${row.file}` });
      continue;
    }
    const attrRe = new RegExp(`${row.attr}="([^"]+)"`, 'g');
    const marks = [];
    for (const m of src.matchAll(attrRe)) {
      if (/[$`{}]/.test(m[1])) continue;
      marks.push({ idx: m.index, name: m[1] });
    }
    for (let i = 0; i < marks.length; i++) {
      const end = i + 1 < marks.length ? marks[i + 1].idx : undefined;
      const chunk = src.slice(marks[i].idx, end);
      const seen = new Map();
      for (const t of titleKeysIn(chunk, row.slots)) {
        if (!seen.has(t.key)) seen.set(t.key, []);
        seen.get(t.key).push(t.slot);
      }
      for (const [key, slots] of seen) {
        if (slots.length < 2) continue;
        hits.push({
          kind: 'title',
          key: `title::${row.page}::${row.file}::${marks[i].name}::${key}`,
          page: row.page,
          detail: `容器「${marks[i].name}」内同一键 ${key} 占了 ${slots.length} 个标题槽（${slots.join(' + ')}）⇒ 同页把同一栏目名渲染两次`,
        });
      }
      notes.push(`${row.page} 容器「${marks[i].name}」标题槽 ${[...seen.keys()].length} 个`);
    }
  }
  return { hits, notes };
}

// ── ③ 信息组渲染点判定面（表 2）─────────────────────────────────────────
function loadInfoGroups() {
  const body = section(specText, '表 2 · 信息组渲染点判定面', ['###', '## ']);
  if (body === null) return { ok: false, reason: '规范缺「表 2 · 信息组渲染点判定面」', rows: [] };
  const rows = [];
  for (const line of body.split('\n')) {
    const cells = rowCells(line);
    if (!cells || cells.length < 5) continue;
    if (/^判定面/.test(cells[0])) continue;
    if (!/^\d+$/.test(cells[3])) continue;
    rows.push({
      page: cells[0], file: cells[1], group: cells[2], maxPoints: Number(cells[3]),
      symbols: cells[4].split(',').map((s) => s.trim().replace(/`/g, '')).filter(Boolean),
    });
  }
  if (!rows.length) return { ok: false, reason: '「表 2 · 信息组渲染点判定面」表体为空', rows: [] };
  return { ok: true, rows };
}

function checkInfoGroups(rows) {
  const hits = [];
  const notes = [];
  for (const row of rows) {
    const { src } = readTarget(row.file);
    if (src === null) {
      hits.push({ kind: 'group', key: `group::${row.page}::${row.file}::missing`, page: row.page, detail: `文件不存在：${row.file}` });
      continue;
    }
    const present = row.symbols.filter((s) => src.includes(s));
    notes.push(`${row.page}「${row.group}」渲染点 ${present.length}/${row.maxPoints}`);
    if (present.length > row.maxPoints) {
      const fp = createHash('sha1').update(present.join(',')).digest('hex').slice(0, 10);
      hits.push({
        kind: 'group',
        key: `group::${row.page}::${row.file}::${row.group}::${fp}`,
        page: row.page,
        detail: `信息组「${row.group}」渲染点 ${present.length} > 允许 ${row.maxPoints}：${present.join(' + ')} ⇒ 同一份信息在同一页面面渲染多次`,
      });
    }
  }
  return { hits, notes };
}

// ── 主流程 ──────────────────────────────────────────────────────────────

const budgets = loadBudgets();
const titleFaces = loadTitleFaces();
const infoGroups = loadInfoGroups();
const specErrors = [budgets, titleFaces, infoGroups].filter((x) => !x.ok).map((x) => x.reason);
if (specErrors.length) {
  console.error(`环境错误：规范登记表不可用 —— ${specErrors.join('；')}`);
  process.exit(2);
}

const budgetResult = checkBudgets(budgets.rows);
const titleResult = checkTitleUniqueness(titleFaces.rows);
const groupResult = checkInfoGroups(infoGroups.rows);
const allHits = [...budgetResult.hits, ...titleResult.hits, ...groupResult.hits];

if (ARGS.has('--list')) {
  console.log(`命中 ${allHits.length} 条（未做基线比较）`);
  console.log(`  ① 折叠预算：${budgetResult.hits.length} 条 · ${budgetResult.notes.join(' | ')}`);
  console.log(`  ② 标题唯一：${titleResult.hits.length} 条`);
  console.log(`  ③ 信息组渲染点：${groupResult.hits.length} 条 · ${groupResult.notes.join(' | ')}`);
  for (const h of allHits) console.log(`  [${h.kind}] ${h.page} — ${h.detail}`);
  process.exit(0);
}

if (ARGS.has('--update-baseline')) {
  const payload = {
    _comment: 'check-ui-structure.mjs 存量基线（ui-design-requirements 批 2026-11-18）：'
      + '每条 = 一条**判定留存**的重复/超预算命中（作者 2026-11-18 16:45 点名的形态：'
      + '「高级 高级」「关于 关于」= title；「底部的账户栏 信息重复」= group）。'
      + '按 disclosure-dedup-spec.md §5，只许降不许升；加条目 = 声称「这条重复是对的」，'
      + '必须在批报告里点名理由。重锚：node scripts/check-ui-structure.mjs --update-baseline',
    generatedAt: new Date().toISOString(),
    total: allHits.length,
    allow: allHits.map((h) => ({ key: h.key, kind: h.kind, page: h.page, detail: h.detail })),
  };
  writeFileSync(BASELINE_PATH, JSON.stringify(payload, null, 2) + '\n');
  console.log(`基线已写入 ${relative(ROOT, BASELINE_PATH)}（${allHits.length} 条：`
    + `${allHits.filter((h) => h.kind === 'title').length} title / `
    + `${allHits.filter((h) => h.kind === 'group').length} group / `
    + `${allHits.filter((h) => h.kind === 'budget').length} budget）`);
  process.exit(0);
}

const baseline = existsSync(BASELINE_PATH) ? JSON.parse(readFileSync(BASELINE_PATH, 'utf8')) : null;
const allowSet = new Set((baseline?.allow || []).map((a) => a.key));
const fails = allHits.filter((h) => !allowSet.has(h.key));
const stale = (baseline?.allow || []).filter((a) => !allHits.some((h) => h.key === a.key));

if (ARGS.has('--json')) {
  console.log(JSON.stringify({
    ok: fails.length === 0,
    budgets: budgetResult.notes,
    hits: allHits.length,
    baseline: baseline?.allow?.length ?? 0,
    fails,
    stale,
  }, null, 2));
  process.exit(fails.length ? 1 : 0);
}

if (fails.length) {
  console.error('✗ ui-structure gate FAILED');
  console.error(`  ① 折叠预算 ${budgetResult.hits.length} 条 · ② 标题唯一 ${titleResult.hits.length} 条 · ③ 信息组渲染点 ${groupResult.hits.length} 条`);
  console.error(`  命中 ${allHits.length} · 存量 ${baseline?.allow?.length ?? 0} · 违规 ${fails.length}`);
  for (const h of fails) console.error(`  [${h.kind}] ${h.page} — ${h.detail}`);
  console.error('\n处置：按 disclosure-dedup-spec.md §3 —— 标题重复留一个（组标题或卡标题，不留两个）；');
  console.error('同一信息组只留一个渲染点，其余面改成指针/挂载；超预算就先收进折叠层。');
  process.exit(1);
}

for (const a of stale) console.log(`  ↑ 存量条目已不存在（改善，建议下次 --update-baseline 收紧）：${a.page} — ${a.detail}`);
console.log(`✓ ui-structure gate passed — 命中 ${allHits.length} 全部落在存量基线内（${baseline?.allow?.length ?? 0} 条）；${budgetResult.notes.join(' · ')}`);
process.exit(0);
