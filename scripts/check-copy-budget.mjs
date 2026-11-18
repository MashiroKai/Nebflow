#!/usr/bin/env node
// check-copy-budget.mjs — 解释性文字「长度预算」静态哨兵
// （ui-design-requirements 批 2026-11-18；作者令逐字：
//  「解释性文字太多了」＋「我们要形成解释性文字的统一设计规范和写作要求在插件里」。）
//
// Why a gate：解释性文字此前只有**有没有**的纪律（stand-dev 铁律 7/8：是事实还是教学），
// 没有**多长**的纪律。于是 hints 一路长成整段小字（实测本仓最长一条 95 字的三句小字），
// 每一批都「顺手把话说全」，没有机器判据能把新增的超长 hint 拦下来。
// 两条轴独立（见 explanatory-copy-spec.md §0）：
//   轴 1 是事实还是教学 → check-self-explaining.mjs（词表 + 存量基线）
//   轴 2 写成多长、放哪档 → **本脚本**（预算表 + 存量基线）
// 本脚本只判轴 2：**键名段 → 位置档 → 字数上限**，逐条 locale 文案值比对。
//
// 数据面 = 规范文件的长度预算表（单源在 stand-dev 插件包内
//   <包根>/references/frontend/explanatory-copy-spec.md 的「## 2. 长度预算表」节
//   （列：键名段 | 位置档 | zh 上限 | en 上限 | 说明）。包根定位见
//   scripts/lib/design-spec-source.mjs；本仓不再保留第二份拷贝。
//   缺表/空表 = 红（没规范就是没约束）。
// 判定面 = scripts/lib/locale-entries.mjs（locale 文案值，单源；与 check-self-explaining
// 同一份实现）。**代码注释不算**。
//
// 用法：
//   node scripts/check-copy-budget.mjs                  # 门禁（红/绿）
//   node scripts/check-copy-budget.mjs --json           # 机器读
//   node scripts/check-copy-budget.mjs --list           # 只列命中（不做基线比较；人工评审用）
//   node scripts/check-copy-budget.mjs --duplicates     # 同值多键清单（U4 人工评审输入，不判红）
//   node scripts/check-copy-budget.mjs --update-baseline # 重锚存量（只许在把命中降下来时用）
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readLocales } from './lib/locale-entries.mjs';
import { resolveDesignSpec, specMissingMessage } from './lib/design-spec-source.mjs';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const LOCALES_DIR = join(WEB, 'js', 'locales');
const SPEC_PATH = resolveDesignSpec('references/frontend/explanatory-copy-spec.md');
const BASELINE_PATH = join(ROOT, 'scripts', 'copy-budget-baseline.json');
const ARGS = new Set(process.argv.slice(2));

if (!SPEC_PATH) {
  console.error(specMissingMessage('references/frontend/explanatory-copy-spec.md'));
  process.exit(2);
}

const NUM = /^\d+$/;
const specTextRaw = readFileSync(SPEC_PATH, 'utf8');

/**
 * 规范「长度预算表」→ [{ seg, tier, zhCap, enCap }]（表序 = 优先级，先匹配先命中）。
 */
function loadBudget() {
  const text = specTextRaw;
  const idx = text.indexOf('## 2. 长度预算表');
  if (idx < 0) return { ok: false, reason: '规范文件缺「## 2. 长度预算表」节', rows: [] };
  const section = text.slice(idx).split('\n## ')[0];
  const rows = [];
  for (const raw of section.split('\n')) {
    const line = raw.trim();
    if (!line.startsWith('|')) continue;
    const cells = line.replace(/^\|/, '').replace(/\|$/, '').split('|').map((s) => s.trim());
    if (cells.length < 4) continue;
    if (/^键名段/.test(cells[0])) continue;              // 表头
    if (/^-{2,}$/.test(cells[0].replace(/[:\s]/g, ''))) continue; // 分隔行
    const seg = cells[0].replace(/`/g, '');
    if (!seg || !NUM.test(cells[2]) || !NUM.test(cells[3])) continue;
    rows.push({ seg, tier: cells[1], zhCap: Number(cells[2]), enCap: Number(cells[3]) });
  }
  if (!rows.length) return { ok: false, reason: '「## 2. 长度预算表」表体为空（或列不是 键名段|位置档|zh 上限|en 上限|说明）', rows: [] };
  return { ok: true, rows };
}

/** 键 → 命中的预算档（按表序；匹配 = 末段不区分大小写包含）。 */
function budgetFor(key, rows) {
  const last = key.split('.').pop().toLowerCase();
  return rows.find((r) => last.includes(r.seg.toLowerCase())) || null;
}

/**
 * 规范「2.1 排除面」→ 前缀数组。排除面 = 不是界面解释文字的键（如 content.* 内容目录），
 * 长度归各自的规范。缺表 = 空（不判红）——排除面是收窄项，不是判据本体。
 */
function loadExcludePrefixes() {
  const at = specTextRaw.indexOf('### 2.1 排除面');
  if (at < 0) return [];
  const body = specTextRaw.slice(at).split('\n## ')[0].split('\n### ')[0];
  const out = [];
  for (const line of body.split('\n')) {
    const t = line.trim();
    if (!t.startsWith('|')) continue;
    const cells = t.replace(/^\|/, '').replace(/\|$/, '').split('|').map((s) => s.trim());
    if (cells.length < 2) continue;
    if (/^键前缀/.test(cells[0])) continue;
    if (/^-{2,}$/.test(cells[0].replace(/[:\s]/g, ''))) continue;
    const prefix = cells[0].replace(/`/g, '');
    if (prefix) out.push(prefix);
  }
  return out;
}

const budget = loadBudget();
if (!budget.ok) {
  console.error(`环境错误：长度预算表不可用 —— ${budget.reason}`);
  process.exit(2);
}

const { names, byFile } = readLocales(LOCALES_DIR);
if (!names.length) {
  console.error(`环境错误：找不到 ${relative(ROOT, LOCALES_DIR)} 下的 locale 文件`);
  process.exit(2);
}

const excludes = loadExcludePrefixes();
const isExcluded = (key) => excludes.some((p) => key.startsWith(p));

const capFor = (key, lang) => {
  if (isExcluded(key)) return null;
  const row = budgetFor(key, budget.rows);
  if (!row) return null;
  return { tier: row.tier, cap: lang === 'en' ? row.enCap : row.zhCap };
};

const hits = [];
const dupGroups = [];
for (const name of names) {
  const lang = name.startsWith('en') ? 'en' : 'zh';
  const rel = `src/main/resources/web/js/locales/${name}`;
  const entries = byFile.get(name);
  const byValue = new Map();
  for (const e of entries) {
    if (!byValue.has(e.value)) byValue.set(e.value, []);
    byValue.get(e.value).push(e.key);
    const c = capFor(e.key, lang);
    if (!c) continue;
    const measured = [...e.value].length;
    if (measured > c.cap) {
      hits.push({ file: rel, lang, key: e.key, tier: c.tier, measured, cap: c.cap, text: e.value });
    }
  }
  for (const [value, keys] of byValue) {
    if (keys.length > 1) dupGroups.push({ file: rel, value, keys });
  }
}

const idOf = (h) => `${h.file}::${h.key}::${h.lang}`;

if (ARGS.has('--duplicates')) {
  console.log(`同值多键：${dupGroups.length} 组（**不判红** —— 值相同 ≠ 同页重复，见 explanatory-copy-spec / disclosure-dedup-spec §3.4，逐组人工判）`);
  for (const g of dupGroups) console.log(`  「${g.value}」\n      ${g.keys.join(' , ')}`);
  process.exit(0);
}

if (ARGS.has('--list')) {
  console.log(`长度预算命中 ${hits.length} 条（未做基线比较）；预算档 ${budget.rows.length} 档，locale ${names.length} 个`);
  for (const h of hits) {
    console.log(`  ${h.file}:${h.key}  [${h.tier}] ${h.measured} > ${h.cap}\n      ${h.text}`);
  }
  process.exit(0);
}

if (ARGS.has('--update-baseline')) {
  const payload = {
    _comment: 'check-copy-budget.mjs 存量基线（ui-design-requirements 批 2026-11-18）：'
      + '每条 = 一条**判定留存**的超预算解释性文字（轴 2 存量；轴 1 的判定在 self-explaining-baseline.json）。'
      + '按 explanatory-copy-spec.md §5，只许降不许升；加条目 = 声称「这句虽超预算但必须这么长」，'
      + '必须在批报告里点名理由。重锚：node scripts/check-copy-budget.mjs --update-baseline',
    generatedAt: new Date().toISOString(),
    budget: budget.rows,
    total: hits.length,
    allow: hits.map((h) => ({ key: idOf(h), file: h.file, localeKey: h.key, lang: h.lang, tier: h.tier, measured: h.measured, cap: h.cap, text: h.text.slice(0, 200) })),
  };
  writeFileSync(BASELINE_PATH, JSON.stringify(payload, null, 2) + '\n');
  console.log(`基线已写入 ${relative(ROOT, BASELINE_PATH)}（${hits.length} 条）`);
  process.exit(0);
}

const baseline = existsSync(BASELINE_PATH) ? JSON.parse(readFileSync(BASELINE_PATH, 'utf8')) : null;
const allowSet = new Set((baseline?.allow || []).map((a) => a.key));
const fails = hits.filter((h) => !allowSet.has(idOf(h)));
const stale = (baseline?.allow || []).filter((a) => !hits.some((h) => idOf(h) === a.key));

if (ARGS.has('--json')) {
  console.log(JSON.stringify({
    ok: fails.length === 0,
    budgetRows: budget.rows.length,
    locales: names.length,
    hits: hits.length,
    baseline: baseline?.allow?.length ?? 0,
    fails,
    stale,
    duplicates: dupGroups.length,
  }, null, 2));
  process.exit(fails.length ? 1 : 0);
}

const byTier = {};
for (const h of hits) byTier[h.tier] = (byTier[h.tier] || 0) + 1;
const summary = Object.entries(byTier).map(([k, v]) => `${k}×${v}`).join(' ') || '无';

if (fails.length) {
  console.error('✗ copy-budget gate FAILED');
  console.error(`  locale ${names.length} 个 · 预算档 ${budget.rows.length} · 命中 ${hits.length}（${summary}）· 存量 ${baseline?.allow?.length ?? 0} · 违规 ${fails.length}`);
  for (const h of fails.slice(0, 200)) {
    console.error(`  ${h.file}:${h.key}  [${h.tier}] 实测 ${h.measured} 字 > 上限 ${h.cap}\n      ${h.text}`);
  }
  if (fails.length > 200) console.error(`  … 其余 ${fails.length - 200} 条同判据`);
  console.error('\n处置：按 explanatory-copy-spec.md §2 —— L1 超限收进 L2 折叠帮助，L2 超限收进 L3 独立页，');
  console.error('或按 §3 W1–W6 收紧成一句一事实。确需例外 = 进 baseline 并在批报告点名理由。');
  process.exit(1);
}

for (const a of stale) console.log(`  ↑ 存量条目已不存在（改善，建议下次 --update-baseline 收紧）：${a.file}:${a.localeKey}`);
console.log(`✓ copy-budget gate passed — locale ${names.length} 个 · 命中 ${hits.length}（${summary}）全部落在存量基线内（${baseline?.allow?.length ?? 0} 条）`);
process.exit(0);
