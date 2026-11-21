#!/usr/bin/env node
// check-button-source.mjs — 按钮「唯一来源」静态哨兵（B1 内联绕过 / B2 登记同步）
// （ui-design-requirements 批 2026-11-18；作者令逐字：
//  「全站按钮必须来自统一按钮组件/规格；出现不统一按钮（内联自定义样式绕过统一组件）
//   必须能被 lint / 静态闸 / 测试拦住」＋「新按钮加入时必须同步更新统一规格与检测」。）
//
// Why a gate：按钮统一的机制已经有（令牌族 `--btn-*` + 基类 `.ds-btn` + 共享态层
// `css/flatButtons.css`），但「统一」此前只有**入口纪律**、没有**出口判据**：
//   · 谁都可以在 JS/HTML 里给按钮写一段 style="…" 把造型就地改掉，绕过整套组件；
//   · 谁都可以在 CSS 里给共享态层加一个族，而不回填规范文件（或反过来）。
// 于是「按钮风格统一」每次评审都要靠人肉 diff 469 处 box-shadow 之外的第二批地方。
// 本脚本把两件事变成判据：
//
//   B1 禁内联造型绕过 —— HTML/JS 里按钮面元素带 style="…"，且 style 含造型属性
//      （background*/color/border*/padding*/font-size/opacity/transform/transition）
//      ⇒ 红（存量记 button-source-baseline.json，只许降不许升）。
//   B2 禁登记漂移 —— `css/flatButtons.css` 的登记族集合必须与规范文件
//      `flat-button-spec.md` §6「按钮族登记表」**逐名同集合**（双向，空集合也算没规范）
//      ⇒ 任一向多一个或少一个即红。
//
// 规范文件是单一真源：改判据改 flat-button-spec.md §5/§6，改存量改 baseline JSON；
// 脚本只是执行器。「按钮面选择器」的判定与 CSS 规则块遍历单源在
//   scripts/lib/button-face.mjs（与 check-flat-buttons.mjs 共用同一份）。
//
// 用法：
//   node scripts/check-button-source.mjs                  # 门禁（红/绿）
//   node scripts/check-button-source.mjs --json           # 机器读
//   node scripts/check-button-source.mjs --list           # 只列命中（不做基线比较）
//   node scripts/check-button-source.mjs --update-baseline # 重锚存量（只许在把命中降下来时用）
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, writeFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { isButtonSelector, walkRules, BUTTON_SEGMENTS, SOFT_SEGMENTS, MODIFIERS } from './lib/button-face.mjs';
import { resolveDesignSpec, specMissingMessage } from './lib/design-spec-source.mjs';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
/** 规范正文（§6 按钮族登记表；单源在仓内 design-language/references/frontend/）。 */
const SPEC_PATH = resolveDesignSpec('references/frontend/flat-button-spec.md');
const FLAT_LAYER = join(WEB, 'css', 'flatButtons.css');
const BASELINE_PATH = join(ROOT, 'scripts', 'button-source-baseline.json');
const ARGS = new Set(process.argv.slice(2));

if (!SPEC_PATH) {
  console.error(specMissingMessage('references/frontend/flat-button-spec.md'));
  process.exit(2);
}
if (!existsSync(FLAT_LAYER)) {
  console.error(`环境错误：共享态层缺失 ${relative(ROOT, FLAT_LAYER)}`);
  process.exit(2);
}

// ── B1：内联造型绕过 ────────────────────────────────────────────────────
/** 造型属性（内联出现即「绕过统一组件」——造型只允许来自 CSS 的令牌族/基类/族文件）。 */
const STYLE_PROPS = /^(background|background-color|background-image|color|border|border-color|border-width|border-style|border-radius|border-top|border-bottom|border-left|border-right|box-shadow|padding|padding-top|padding-bottom|padding-left|padding-right|font-size|font-weight|font-family|line-height|opacity|transform|transition|filter|backdrop-filter)$/;

/** 类名里是否含按钮面标记段（与 lib 同口径，但用字符串名判定）。 */
function classNameIsButtonFace(name) {
  const segs = name.split(/[-_]/).filter(Boolean).map((s) => s.toLowerCase());
  if (segs.some((s) => BUTTON_SEGMENTS.has(s))) return true;
  for (let k = 0; k < segs.length; k++) {
    if (!SOFT_SEGMENTS.has(segs[k])) continue;
    if (segs.slice(k + 1).every((s) => MODIFIERS.has(s))) return true;
  }
  return false;
}

/** 走 web/js 全部 .js 与 index.html。 */
function walkWebFiles() {
  const out = [join(WEB, 'index.html')].filter((p) => existsSync(p));
  const stack = [join(WEB, 'js')];
  while (stack.length) {
    const dir = stack.pop();
    if (!existsSync(dir)) continue;
    for (const entry of readdirSync(dir)) {
      const p = join(dir, entry);
      const st = statSync(p);
      if (st.isDirectory()) stack.push(p);
      else if (entry.endsWith('.js')) out.push(p);
    }
  }
  return out.sort();
}

/** 一行里按钮面元素的 style="…" 造型属性 → 命中。 */
function inlineStyleHits(relPath, line, lineNo) {
  const hits = [];
  for (const m of line.matchAll(/<([a-z][a-z0-9-]*)\b([^>]*?)\bstyle\s*=\s*"([^"]*)"/gi)) {
    const [, tagName, attrs, styleValue] = m;
    const classAttr = (attrs.match(/\bclass\s*=\s*"([^"]*)"/i) || [])[1] || '';
    const isButtonFace = tagName.toLowerCase() === 'button'
      || /\brole\s*=\s*["']?button/i.test(attrs)
      || classAttr.split(/\s+/).filter(Boolean).some(classNameIsButtonFace);
    if (!isButtonFace) continue;
    const props = styleValue.split(';')
      .map((d) => d.split(':')[0].trim().toLowerCase())
      .filter((p) => p && STYLE_PROPS.test(p));
    if (!props.length) continue;
    const snippet = `<${tagName} class="${classAttr}" style="…">`;
    hits.push({
      file: relPath,
      line: lineNo,
      props,
      snippet,
      key: `${relPath}::${createHash('sha1').update(snippet).digest('hex').slice(0, 10)}`,
    });
  }
  return hits;
}

function scanInlineStyles() {
  const hits = [];
  for (const file of walkWebFiles()) {
    const rel = relative(ROOT, file);
    const lines = readFileSync(file, 'utf8').split('\n');
    lines.forEach((line, i) => { hits.push(...inlineStyleHits(rel, line, i + 1)); });
  }
  return hits;
}

// ── B2：登记同步（flatButtons.css ↔ 规范 §6 表）─────────────────────────

/** 共享态层登记表：CSS 选择器里出现的按钮面类/ID 名（注释已剔）。 */
function layerRegistry() {
  const src = readFileSync(FLAT_LAYER, 'utf8');
  const names = new Set();
  for (const rule of walkRules(src)) {
    for (const token of rule.selector.match(/[.#][A-Za-z0-9_-]+/g) || []) names.add(token);
  }
  return names;
}

/** 规范 §6 表里的族名：`| \`.panel-btn\` | … |` 首列反引号内容。 */
function specRegistry() {
  const text = readFileSync(SPEC_PATH, 'utf8');
  const idx = text.indexOf('## 6. 按钮族登记表');
  if (idx < 0) return { ok: false, reason: '规范文件缺「## 6. 按钮族登记表」节', names: new Set() };
  const section = text.slice(idx).split('\n## ')[0];
  const names = new Set();
  for (const raw of section.split('\n')) {
    const line = raw.trim();
    if (!line.startsWith('|')) continue;
    if (/^选择器|^\|?\s*族（选择器）/.test(line)) continue;
    if (/^\|[\s:|-]+\|$/.test(line)) continue;
    const cell = line.replace(/^\|/, '').split('|')[0].trim().replace(/`/g, '');
    if (/^[.#][A-Za-z0-9_-]+$/.test(cell)) names.add(cell);
  }
  if (!names.size) return { ok: false, reason: '「## 6. 按钮族登记表」表体为空（空表也算没规范）', names };
  return { ok: true, names };
}

// ── 主流程 ──────────────────────────────────────────────────────────────

const inlineHits = scanInlineStyles();
const layerNames = layerRegistry();
const spec = specRegistry();

const baseline = existsSync(BASELINE_PATH) ? JSON.parse(readFileSync(BASELINE_PATH, 'utf8')) : null;
const allowSet = new Set((baseline?.allow || []).map((a) => a.key));

const b1Fails = inlineHits.filter((h) => !allowSet.has(h.key));
const b1Stale = (baseline?.allow || []).filter((a) => !inlineHits.some((h) => h.key === a.key));

const onlyInLayer = [...layerNames].filter((n) => !spec.names.has(n)).sort();
const onlyInSpec = [...spec.names].filter((n) => !layerNames.has(n)).sort();
const b2Fails = [
  ...onlyInLayer.map((n) => ({ side: '仅共享态层有', name: n })),
  ...onlyInSpec.map((n) => ({ side: '仅规范 §6 有', name: n })),
];
const b2SpecBad = spec.ok ? [] : [{ side: '规范不可用', name: spec.reason }];

if (ARGS.has('--list')) {
  console.log(`B1 内联造型命中 ${inlineHits.length} 条（未做基线比较）：`);
  for (const h of inlineHits) console.log(`  ${h.file}:${h.line}  [${h.props.join(',')}]  ${h.snippet}`);
  console.log(`B2 共享态层 ${layerNames.size} 族 / 规范 §6 ${spec.names.size} 族；差集 ${b2Fails.length} 条`);
  for (const f of b2Fails) console.log(`  ${f.side}：${f.name}`);
  process.exit(0);
}

if (ARGS.has('--update-baseline')) {
  const payload = {
    _comment: 'check-button-source.mjs 存量基线（ui-design-requirements 批 2026-11-18）：'
      + '每条 = 一处**判定保留**的内联造型按钮面（B1）。按 flat-button-spec.md §5.3，'
      + '存量只许降不许升；加条目 = 声称「这个绕过是有意保留的」，必须在批报告里点名理由。'
      + '重锚：node scripts/check-button-source.mjs --update-baseline',
    generatedAt: new Date().toISOString(),
    total: inlineHits.length,
    allow: inlineHits.map((h) => ({ key: h.key, file: h.file, props: h.props, snippet: h.snippet })),
  };
  writeFileSync(BASELINE_PATH, JSON.stringify(payload, null, 2) + '\n');
  console.log(`基线已写入 ${relative(ROOT, BASELINE_PATH)}（${inlineHits.length} 条）`);
  process.exit(0);
}

if (ARGS.has('--json')) {
  const ok = b1Fails.length === 0 && b2Fails.length === 0 && b2SpecBad.length === 0;
  console.log(JSON.stringify({
    ok,
    b1: { hits: inlineHits.length, baseline: baseline?.allow?.length ?? 0, fails: b1Fails, stale: b1Stale },
    b2: {
      layer: layerNames.size, spec: spec.names.size, specOk: spec.ok,
      onlyInLayer, onlyInSpec,
    },
  }, null, 2));
  process.exit(ok ? 0 : 1);
}

const bad = [...b2SpecBad];
if (bad.length || b1Fails.length || b2Fails.length) {
  console.error('✗ button-source gate FAILED');
  console.error(`  B1 内联造型：命中 ${inlineHits.length} · 存量 ${baseline?.allow?.length ?? 0} · 违规 ${b1Fails.length}`);
  console.error(`  B2 登记同步：共享态层 ${layerNames.size} 族 · 规范 §6 ${spec.names.size} 族 · 差集 ${b2Fails.length}`);
  for (const b of bad) console.error(`  · ${b.name}`);
  for (const h of b1Fails) {
    console.error(`  ${h.file}:${h.line}  [B1 内联造型绕过] ${h.props.join(',')}\n      ${h.snippet}`);
  }
  for (const f of b2Fails) console.error(`  [B2 登记漂移] ${f.side}：${f.name}`);
  console.error('\n处置：造型挪回族文件 + 走 --btn-*/--ds-* 令牌（flat-button-spec.md §5.1）；');
  console.error('新按钮族按 §5.4 四步走（两处同步登记），再跑本闸与 check-flat-buttons.mjs。');
  process.exit(1);
}

for (const a of b1Stale) console.log(`  ↑ 存量条目已不存在（改善，建议下次 --update-baseline 收紧）：${a.file}`);
console.log(`✓ button-source gate passed — B1 内联造型 0 违规（存量 ${baseline?.allow?.length ?? 0} 处已在基线）；`
  + `B2 共享态层 ${layerNames.size} 族 ↔ 规范 §6 ${spec.names.size} 族 同集合`);
process.exit(0);
