#!/usr/bin/env node
// check-design-spec-sync.mjs — 设计规格刻度「spec 表 ↔ 令牌源」常驻防漂移闸
// （design-scale-batch 2026-11-04；作者令逐字：「把我们对圆角 边距 文字离边缘的
//  距离 各文字的大小 写进spec和前端开发包」）。
//
// Why a gate（与 check-design-tokens.mjs 同一判据来源的分工）：
//   · check-design-tokens.mjs 管「消费面禁字面量」——组件里不许散写裸值；
//   · 本闸管「规范面与代码不许互相漂移」——spec 表里登记的（变量名 → 值）必须
//     在令牌源里逐条存在且同值；反过来令牌源里每一个刻度族令牌（--ds-fs-* /
//     --ds-radius-* / --ds-space-*）都必须在 spec 表里有行（新档不许只在代码里
//     出生而不进规范）。两向都红才算闭环：只查一向，就能靠「表里悄悄补一行」
//     或「代码里悄悄加一档」绕过。
//
// 判据三组：
//   ① spec → 源（硬）：spec 表每行的（变量名, 值）在 web/css 里有声明且**所有**
//      声明点同值（亮/暗自适应档同值只算一次；var() 别名解析后比对）。
//   ② 源 → spec（硬）：令牌源里 `--ds-fs-*` / `--ds-radius-*` / `--ds-space-*`
//      族每个变量都必须在 spec 表里有行（含具名槽，如 --ds-fs-bubble）。
//   ③ 账本 → 源（硬）：插件账本 assets/tokens.css 的 `--dl-{fs,r,sp}-*` 与运行时
//      `--ds-{fs,radius,space}-*` 同值（两处同档不同值 = 漂移）。账本覆盖面缺口
//      以 INFO 行报出（本批已补登档外具名槽，缺口应为 0）。
//
// 用法：
//   node scripts/check-design-spec-sync.mjs            # 门禁（红/绿）
//   node scripts/check-design-spec-sync.mjs --json     # 机器读
// 退出码：0 = 绿；1 = 红；2 = 环境错误（spec 文件缺失也算 1，缺表就是没规范）。

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { resolveDesignSpec, specMissingMessage } from './lib/design-spec-source.mjs';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const CSS_DIR = join(ROOT, 'src', 'main', 'resources', 'web', 'css');
/** 规范面（单源在 stand-dev 插件包内；本仓不再保留第二份拷贝）。 */
const SPEC_PATH = resolveDesignSpec('references/frontend/design-tokens-ledger.md');
/** 令牌账本资产（同上，随包走）。 */
const LEDGER_PATH = resolveDesignSpec('assets/design-system/tokens.css');
if (!SPEC_PATH || !LEDGER_PATH) {
  console.error(`design-spec-sync 不可跑 —— ${specMissingMessage('references/frontend/design-tokens-ledger.md + assets/design-system/tokens.css')}`);
  process.exit(2);
}
const AS_JSON = process.argv.includes('--json');

const BEGIN = '<!-- design-spec-scale:begin -->';
const END = '<!-- design-spec-scale:end -->';

const red = [];
const info = [];

// ── 工具 ────────────────────────────────────────────────────────────────
const stripComments = (s) =>
  s.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
const norm = (v) => v.replace(/\s+/g, ' ').trim().replace(/;$/, '');

/** 令牌源：web/css 全量的定制属性声明（名字 → {values:Set, sites:[...]}）。 */
function readTokens() {
  const tokens = new Map();
  for (const f of readdirSync(CSS_DIR).filter((x) => x.endsWith('.css'))) {
    const text = stripComments(readFileSync(join(CSS_DIR, f), 'utf8'));
    const lines = text.split('\n');
    lines.forEach((line, i) => {
      const re = /(--[a-z0-9-]+)\s*:\s*([^;{}]+);/g;
      let m;
      while ((m = re.exec(line))) {
        const name = m[1], value = norm(m[2]);
        const rec = tokens.get(name) || { values: new Set(), sites: [] };
        rec.values.add(value);
        rec.sites.push(`${relative(ROOT, join(CSS_DIR, f))}:${i + 1}`);
        tokens.set(name, rec);
      }
    });
  }
  return tokens;
}

/** `var(--x)` 一跳/多跳解析到具体值；解析不动则原样返回（交给比对报红）。 */
function resolveValue(value, tokens, depth = 0) {
  if (depth > 5) return value;
  const m = /^var\(\s*(--[a-z0-9-]+)\s*\)$/.exec(value.trim());
  if (!m) return value;
  const rec = tokens.get(m[1]);
  if (!rec || !rec.values.size) return value;
  const only = [...rec.values];
  if (only.length !== 1) return value;
  return resolveValue(only[0], tokens, depth + 1);
}

/** spec 表：marker 块内的 `| 变量 | 值 | 用在哪 |` 行。 */
function readSpec() {
  if (!existsSync(SPEC_PATH)) {
    return { error: `spec 文件缺失：${SPEC_PATH}` };
  }
  const text = readFileSync(SPEC_PATH, 'utf8');
  const b = text.indexOf(BEGIN);
  const e = text.indexOf(END);
  if (b < 0 || e < 0 || e < b) {
    return { error: `spec 机械校验块缺失（需 ${BEGIN} … ${END}）：${SPEC_PATH}` };
  }
  const rows = [];
  for (const raw of text.slice(b + BEGIN.length, e).split('\n')) {
    const line = raw.trim();
    if (!line.startsWith('|')) continue;
    const cells = line.split('|').slice(1, -1).map((c) => c.trim());
    if (cells.length < 2) continue;
    const name = cells[0].replace(/`/g, '');
    const value = cells[1].replace(/`/g, '');
    if (!name.startsWith('--')) continue; // 表头/分隔行
    if (/^-+$/.test(value)) continue;
    rows.push({ name, value, note: (cells[2] || '').replace(/\s+/g, ' ') });
  }
  if (!rows.length) return { error: 'spec 机械校验块内没有任何（变量名 → 值）行' };
  return { rows };
}

/** 账本 --dl-* 读取。 */
function readLedger() {
  if (!existsSync(LEDGER_PATH)) return new Map();
  const text = stripComments(readFileSync(LEDGER_PATH, 'utf8'));
  const m = new Map();
  const re = /(--dl-[a-z0-9-]+)\s*:\s*([^;{}]+);/g;
  let g;
  while ((g = re.exec(text))) {
    const name = g[1], value = norm(g[2]);
    const rec = m.get(name) || new Set();
    rec.add(value);
    m.set(name, rec);
  }
  return m;
}

/** --dl-* ↔ --ds-* 对位（账本命名族 → 运行时命名族）。 */
function ledgerToRuntime(dlName) {
  const suffix = dlName.replace(/^--dl-/, '');
  if (suffix.startsWith('r-')) return `--ds-radius-${suffix.slice(2)}`;
  if (suffix.startsWith('fs-')) return `--ds-fs-${suffix.slice(3)}`;
  if (suffix.startsWith('sp-')) return `--ds-space-${suffix.slice(3)}`;
  return null;
}

// ── ① spec → 源 ─────────────────────────────────────────────────────────
const tokens = readTokens();
const spec = readSpec();
if (spec.error) {
  red.push(`① spec 读取失败：${spec.error}`);
} else {
  const specNames = new Set();
  for (const row of spec.rows) {
    specNames.add(row.name);
    const rec = tokens.get(row.name);
    if (!rec) {
      red.push(`① ${row.name} 在令牌源里不存在（spec 表登记值 ${row.value}）`);
      continue;
    }
    // 两侧都解析 var() 别名后比对（派生槽 --ds-card-gap = var(--ds-space-3) 属合法写法）
    const specResolved = resolveValue(row.value, tokens);
    const bad = [...rec.values].filter((v) => resolveValue(v, tokens) !== specResolved);
    if (bad.length) {
      red.push(
        `① ${row.name} 值不一致：spec=${row.value}（解析 ${specResolved}）`
        + ` 源=${[...rec.values].join(' | ')}（声明点 ${rec.sites.slice(0, 3).join(', ')}）`,
      );
    } else if (specResolved === row.value && /^var\(/.test(row.value)) {
      red.push(`① ${row.name} 的 spec 值 ${row.value} 无法解析成具体值（别名断链）`);
    }
  }

  // ── ② 源 → spec（刻度族令牌必须进表）────────────────────────────────
  const LADDER = /^--ds-(fs|radius|space)-[a-z0-9-]+$/;
  for (const [name, rec] of tokens) {
    if (!LADDER.test(name)) continue;
    if (specNames.has(name)) continue;
    red.push(`② ${name}（源值 ${[...rec.values].join(' | ')}）未登记进 spec 表 —— 新档不许只在代码里出生`);
  }
}

// ── ③ 账本 → 源 ─────────────────────────────────────────────────────────
const ledger = readLedger();
let ledgerChecked = 0;
for (const [dl, values] of ledger) {
  const ds = ledgerToRuntime(dl);
  if (!ds) continue;
  const rec = tokens.get(ds);
  const dlVals = [...values];
  if (!rec) {
    red.push(`③ 账本 ${dl}=${dlVals.join(' | ')} 在运行时找不到对位令牌 ${ds}`);
    continue;
  }
  ledgerChecked += 1;
  const dsVals = [...rec.values].map((v) => resolveValue(v, tokens));
  const mismatch = dlVals
    .map((v) => resolveValue(v, tokens))
    .filter((v, i) => v !== dsVals[i] && !dsVals.includes(v));
  if (mismatch.length) {
    red.push(`③ 账本与运行时不同值：${dl}=${dlVals.join(' | ')} vs ${ds}=${dsVals.join(' | ')}`);
  }
}
if (spec.error === undefined) {
  const specFs = spec.rows.filter((r) => /^--ds-(fs|radius|space)-/.test(r.name));
  const ledgerCovered = specFs.filter((r) => {
    const dl = r.name
      .replace(/^--ds-radius-/, '--dl-r-')
      .replace(/^--ds-fs-/, '--dl-fs-')
      .replace(/^--ds-space-/, '--dl-sp-');
    return ledger.has(dl);
  });
  const missing = specFs.filter((r) => !ledgerCovered.includes(r));
  if (missing.length) {
    info.push(`账本未收编的刻度行 ${missing.length} 条：${missing.map((r) => r.name).join(', ')}`);
  }
}

// ── 输出 ────────────────────────────────────────────────────────────────
const ok = red.length === 0;
if (AS_JSON) {
  console.log(JSON.stringify({
    gate: 'design-spec-sync',
    ok,
    spec: SPEC_PATH,
    specRows: spec.rows ? spec.rows.length : 0,
    ledgerChecked,
    red,
    info,
  }, null, 2));
} else {
  console.log('design-spec-sync —— 设计规格刻度 spec ↔ 令牌源 防漂移闸');
  console.log(`  spec 表：${SPEC_PATH}`);
  console.log(`  spec 行数：${spec.rows ? spec.rows.length : 0} · 账本对位比对：${ledgerChecked} 条`);
  for (const i of info) console.log(`  INFO ${i}`);
  if (ok) {
    console.log('PASS —— spec 表与令牌源逐条同值，刻度族令牌全部在表（无漂移）');
  } else {
    console.log(`FAIL —— ${red.length} 处漂移：`);
    for (const r of red) console.log(`  · ${r}`);
  }
}
process.exit(ok ? 0 : 1);
