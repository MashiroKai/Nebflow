#!/usr/bin/env node
// check-flat-buttons.mjs — 按钮扁平化「禁厚度」静态哨兵
// （flat-button-everywhere 批 2026-11-05；作者令逐字：
//  「我们的按钮扁平化风格要求要落实到各处 不管是hover还是点击 或者按下。」）
//
// Why a gate: 扁平不是「某个弹窗改一次」的风格偏好，而是**全站按钮面的材质契约**。
// 本批之前，同一个按钮在不同文件里各自造厚度（.g2-pill-primary 的镜面渐变 +
// inset 高光 + 浮影、.ds-btn 的 --ds-shadow-control、大量 .*-btn 的 hover 抬影），
// 改一处漏十处，且没有任何机器判据能发现「有个按钮又长回去了」。手工审查抓不住
// 469 处 box-shadow 里的按钮子集，所以把「按钮规则里不许出现厚度道具」变成判据。
//
// 判据（对**按钮面选择器**命中的规则块逐条声明扫描）：
//   F1 box-shadow      任何非 none 的投影（含 inset 浮雕、含焦点环 shadow）⇒ 红
//   F2 text-shadow     任何非 none ⇒ 红
//   F3 渐变            background/background-image 里出现 *gradient( ⇒ 红
//                      （上下明暗造立体 = 渐变，扁平禁用）
//   F4 drop-shadow     filter 里出现 drop-shadow( ⇒ 红
//   F5 物理按压位移    transform 里出现 translateY/translate3d 的 y 轴位移 ⇒ 红
//                      （scale 允许：幅度即 affordance，不造厚度）
//
// 「按钮面选择器」的定义（机械化，不靠人记账）：选择器里任一复合选择器含
//   ① 类型选择器 button，或属性 [type=button|submit|reset]，或
//   ② 类/id 标记的名字段（按 - _ 切分）命中 BUTTON_SEGMENTS 之一。
// 切分口径：`.cfg-btn-primary` → [cfg, btn, primary]；`.ds-btn--primary` →
// [ds, btn, '', primary]；`#send-btn` → [send, btn]。
//
// 例外清单**不在本脚本里**：本脚本从规范文件
//   src/main/resources/seed/plugins/design-language/skills/design-language/
//   references/flat-button-spec.md
// 的「例外清单」表逐行读取（每行 = 选择器 + 属性族 + 理由）。缺表 = 红（没有规范
// 就是没有约束）；表里每条例外必须写明理由，空理由 = 红。改判据要改规范，改豁免
// 也要改规范 —— 脚本只是规范的执行面。
//
// 用法：
//   node scripts/check-flat-buttons.mjs            # 门禁（红/绿）
//   node scripts/check-flat-buttons.mjs --json     # 机器读（逐条命中）
//   node scripts/check-flat-buttons.mjs --inventory # 盘点表（全站按钮面统计）
// 退出码：0 = 绿；1 = 红（违规 or 例外表缺失/空理由）；2 = 环境错误。

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const CSS_DIR = join(ROOT, 'src', 'main', 'resources', 'web', 'css');
const SPEC_PATH = join(
  ROOT, 'src', 'main', 'resources', 'seed', 'plugins', 'design-language',
  'skills', 'design-language', 'references', 'flat-button-spec.md',
);

/** 硬标记段：类/id 名按 [-_] 切分后**任一段**命中即视为按钮面（btn 无歧义）。 */
const BUTTON_SEGMENTS = new Set(['btn', 'btns', 'button', 'buttons']);

/**
 * 软标记段：命中后还要求「其后所有段都是修饰词」才算按钮面。
 * 为什么不能无条件算：`-pill` 在仓里既做按钮（`.g2-pill-primary` / `.dvp-pill`）
 * 也做**非按钮名词**（`.cal-pill-listbox` 是下拉面板、`.cal-pill-option` 是选项行）
 * —— 面板/行有影子是合法的扁平禁令之外的东西（它们是容器不是可点击件）。
 * 判据：软段之后只剩修饰词 ⇒ 按钮（`.g2-pill-primary` 的 primary）；
 *       之后出现实词 ⇒ 不是按钮（`listbox` / `option`）。
 */
const SOFT_SEGMENTS = new Set(['pill', 'pills', 'fab', 'fabs']);

/** 软段之后的合法修饰词（状态/档位/形态，全部不改变「这是一个按钮」的事实）。 */
const MODIFIERS = new Set([
  'primary', 'secondary', 'tertiary', 'danger', 'ghost', 'solid', 'outline', 'flat',
  'sm', 'md', 'lg', 'xl', 'mini', 'tiny', 'big',
  'on', 'off', 'active', 'picked', 'selected', 'disabled', 'open', 'busy',
  'icon', 'toggle', 'group', 'row', 'list', 'block', 'wide', 'full', 'labeled', 'label',
  'chip', 'fab', 'pill',
]);

/** 禁厚度项（判据单一真源；规范文件的 §1 逐字同表）。 */
const FORBIDDEN = [
  {
    id: 'F1',
    prop: /^box-shadow$/,
    test: (v) => v.trim() !== 'none',
    label: 'box-shadow（含 inset 浮雕 / 焦点环影子）',
  },
  {
    id: 'F2',
    prop: /^text-shadow$/,
    test: (v) => v.trim() !== 'none',
    label: 'text-shadow（字面造厚度）',
  },
  {
    id: 'F3',
    prop: /^(background|background-image|background-color)$/,
    test: (v) => /(?:repeating-)?(?:linear|radial|conic)-gradient\s*\(/i.test(v),
    label: '渐变（*gradient( 造明暗立体）',
  },
  {
    id: 'F4',
    prop: /^(filter|-webkit-filter)$/,
    test: (v) => /drop-shadow\s*\(/i.test(v),
    label: 'filter: drop-shadow（投影）',
  },
  {
    id: 'F5',
    prop: /^(transform|-webkit-transform)$/,
    test: (v) => /translateY\s*\(/i.test(v) || /translate3d\s*\([^)]*,[^,)]*[1-9]/.test(v),
    label: 'translateY 物理按压位移（与扁平契约冲突）',
  },
];

// ── 解析 ────────────────────────────────────────────────────────────────

/** 去掉注释但保留行号（用等量换行替换）。 */
function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}

/**
 * 极简 CSS 块遍历器：产出 { selector, decls:[{prop,value,line}], line, atStack }。
 * 只关心「规则块」；at-rule 的 prelude 压栈，用来给命中行标注上下文（例如
 * `@media (hover: hover)` 或 `html[data-theme="dark"]`），不改变匹配语义。
 */
function* walkRules(src) {
  const text = stripComments(src);
  const lineOf = (idx) => text.slice(0, idx).split('\n').length;
  let i = 0;
  const stack = []; // { prelude, type: 'at' | 'rule', braceDepth }
  let depth = 0;
  let selStart = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === '{') {
      const prelude = text.slice(selStart, i).trim();
      if (prelude.startsWith('@')) {
        stack.push({ prelude, type: 'at', depth });
        depth++;
        i++;
        selStart = i;
        continue;
      }
      // 规则块：读到匹配的 }
      const bodyStart = i + 1;
      let j = bodyStart;
      let inner = 0;
      while (j < text.length) {
        if (text[j] === '{') inner++;
        else if (text[j] === '}') {
          if (inner === 0) break;
          inner--;
        }
        j++;
      }
      const body = text.slice(bodyStart, j);
      const decls = [];
      let cur = '';
      let declStart = bodyStart;
      for (let k = 0; k <= body.length; k++) {
        const c = body[k];
        if (c === ';' || k === body.length) {
          const raw = cur;
          const colon = raw.indexOf(':');
          if (colon > 0) {
            const prop = raw.slice(0, colon).trim().toLowerCase();
            const value = raw.slice(colon + 1).trim();
            decls.push({ prop, value, line: lineOf(declStart) });
          }
          cur = '';
          declStart = bodyStart + k + 1;
        } else {
          if (c === '\n') declStart = bodyStart + k + 1;
          cur += c;
        }
      }
      yield {
        selector: prelude,
        decls,
        line: lineOf(selStart),
        atStack: stack.filter((s) => s.type === 'at').map((s) => s.prelude),
      };
      i = j + 1;
      selStart = i;
      continue;
    }
    if (ch === '}') {
      depth = Math.max(0, depth - 1);
      if (stack.length && stack[stack.length - 1].type === 'at') stack.pop();
      i++;
      selStart = i;
      continue;
    }
    i++;
  }
}

/**
 * 取选择器的**主题复合**（最右复合选择器 = 被样式化元素本身）——CSS 匹配语义
 * 决定的：`.fwd-btn:hover .fwd-tip` 的样式落在 tooltip 上，不是落在按钮上，
 * 拿整串判按钮会把「提示气泡入场位移」误判成「按钮物理按压」。
 * 伪元素（::before/::after 等）属于按钮自身渲染面，算。
 */
function subjectCompound(part) {
  // 按「组合符」切分（后代空格 / > + ~），跳过括号与引号内部
  let depth = 0;
  let last = 0;
  for (let i = 0; i < part.length; i++) {
    const c = part[i];
    if (c === '(' || c === '[') depth++;
    else if (c === ')' || c === ']') depth = Math.max(0, depth - 1);
    else if (depth === 0 && /[\s>+~]/.test(c)) {
      // 空格要连续吞掉
      let j = i;
      while (j < part.length && /[\s>+~]/.test(part[j])) j++;
      last = j;
      i = j - 1;
    }
  }
  return part.slice(last).trim();
}

/** 选择器里是否含按钮面（含其修饰）。 */
function isButtonSelector(selector) {
  for (const part of selector.split(',')) {
    const comp = subjectCompound(part);
    if (!comp) continue;
    if (/(^|[\s>+~])button(?![\w-])/.test(comp)) return true;
    if (/\[type\s*=\s*["']?(button|submit|reset)["']?\]/i.test(comp)) return true;
    const names = comp.match(/[.#][A-Za-z0-9_-]+/g) || [];
    for (const n of names) {
      const segs = n.slice(1).split(/[-_]/).filter(Boolean).map((s) => s.toLowerCase());
      if (segs.some((s) => BUTTON_SEGMENTS.has(s))) return true;
      for (let k = 0; k < segs.length; k++) {
        if (!SOFT_SEGMENTS.has(segs[k])) continue;
        const tail = segs.slice(k + 1);
        if (tail.every((s) => MODIFIERS.has(s))) return true;
      }
    }
  }
  return false;
}

// ── 例外清单（规范文件 → 数据）────────────────────────────────────────

/**
 * 规范文件「例外清单」表格式（Markdown，逐行一条）：
 *   | `选择器片段` | F1 | 理由 |
 * 解析到 { selector, rules:Set<F*>, why }。理由为空 ⇒ 记为无效例外（红）。
 */
function loadExceptions() {
  if (!existsSync(SPEC_PATH)) {
    return { ok: false, reason: `规范文件缺失：${relative(ROOT, SPEC_PATH)}`, list: [] };
  }
  const text = readFileSync(SPEC_PATH, 'utf8');
  const idx = text.indexOf('## 例外清单');
  if (idx < 0) {
    return { ok: false, reason: '规范文件缺「## 例外清单」节', list: [] };
  }
  const section = text.slice(idx).split('\n## ')[0];
  const list = [];
  for (const raw of section.split('\n')) {
    const line = raw.trim();
    if (!line.startsWith('|')) continue;
    const cells = line.replace(/^\|/, '').replace(/\|$/, '').split('|').map((s) => s.trim());
    if (cells.length < 3) continue;
    if (/^-{2,}$/.test(cells[0].replace(/[:\s]/g, ''))) continue;
    if (/^选择器/.test(cells[0])) continue; // 表头
    const sel = cells[0].replace(/`/g, '').trim();
    const ids = (cells[1].match(/F\d/g) || []).map((s) => s.toUpperCase());
    const why = cells.slice(2).join(' ').trim();
    // `（无）` = 哨兵行：声明「本批零例外」，不是一条例外。理由同样必须写清。
    if (/^（无）$|^\(none\)$/i.test(sel)) {
      list.push({ selector: sel, rules: ids, why, valid: Boolean(why), sentinel: true });
      continue;
    }
    list.push({ selector: sel, rules: ids, why, valid: Boolean(why) && ids.length > 0 });
  }
  if (!list.length) return { ok: false, reason: '「## 例外清单」节里没有任何例外行（表体为空也算没规范）', list: [] };
  return { ok: true, list };
}

function exceptionCovers(selector, ruleId, exceptions) {
  return exceptions.some(
    (e) => e.valid && !e.sentinel && e.rules.includes(ruleId) && selector.includes(e.selector),
  );
}

// ── 扫描 ────────────────────────────────────────────────────────────────

function scan() {
  const files = readdirSync(CSS_DIR).filter((f) => f.endsWith('.css')).sort();
  const hits = [];
  const buttonBlocks = [];
  for (const file of files) {
    const src = readFileSync(join(CSS_DIR, file), 'utf8');
    for (const rule of walkRules(src)) {
      if (!isButtonSelector(rule.selector)) continue;
      buttonBlocks.push({ file, line: rule.line, selector: rule.selector, atStack: rule.atStack });
      for (const d of rule.decls) {
        for (const f of FORBIDDEN) {
          if (!f.prop.test(d.prop)) continue;
          if (!f.test(d.value)) continue;
          hits.push({
            file,
            line: d.line,
            selector: rule.selector.trim().replace(/\s+/g, ' '),
            ruleId: f.id,
            label: f.label,
            prop: d.prop,
            value: d.value.replace(/\s+/g, ' ').slice(0, 160),
            atStack: rule.atStack,
          });
        }
      }
    }
  }
  return { hits, buttonBlocks, files };
}

// ── 主流程 ──────────────────────────────────────────────────────────────

const argv = process.argv.slice(2);
const jsonOut = argv.includes('--json');
const inventory = argv.includes('--inventory');

const exceptions = loadExceptions();
const { hits, buttonBlocks, files } = scan();
const effective = hits.filter((h) => !exceptionCovers(h.selector, h.ruleId, exceptions.list));

if (jsonOut) {
  console.log(JSON.stringify({
    specPath: relative(ROOT, SPEC_PATH),
    specOk: exceptions.ok,
    specReason: exceptions.reason || null,
    cssFiles: files.length,
    buttonBlocks: buttonBlocks.length,
    hits: hits.length,
    exempted: hits.length - effective.length,
    violations: effective,
    exceptions: exceptions.list,
  }, null, 2));
  process.exit(exceptions.ok && exceptions.list.every((e) => e.valid) && effective.length === 0 ? 0 : 1);
}

if (inventory) {
  const byFile = new Map();
  for (const b of buttonBlocks) byFile.set(b.file, (byFile.get(b.file) || 0) + 1);
  const byRule = new Map();
  for (const h of hits) {
    const k = byRule.get(h.ruleId) || { n: 0, label: h.label };
    k.n++;
    byRule.set(h.ruleId, k);
  }
  console.log(`CSS 文件：${files.length} · 按钮面规则块：${buttonBlocks.length}`);
  console.log('\n── 按文件（按钮面规则块数）──');
  for (const [f, n] of [...byFile].sort((a, b) => b[1] - a[1])) console.log(`${String(n).padStart(4)}  ${f}`);
  console.log('\n── 按禁项（原始命中数，未扣例外）──');
  for (const [id, v] of [...byRule].sort()) console.log(`${id}  ${String(v.n).padStart(4)}  ${v.label}`);
  console.log(`\n合计命中 ${hits.length} 处；例外清单可覆盖 ${hits.length - effective.length} 处；违规 ${effective.length} 处。`);
  process.exit(0);
}

const bad = [];
if (!exceptions.ok) bad.push(`例外清单不可用：${exceptions.reason}`);
const invalidEx = exceptions.list.filter((e) => !e.valid);
for (const e of invalidEx) {
  bad.push(`例外行无效（缺理由或缺禁项号）：\`${e.selector}\` | ${e.rules.join(',') || '-'} | ${e.why || '(空理由)'}`);
}

if (bad.length || effective.length) {
  console.error('✗ flat-button gate FAILED');
  console.error(`  CSS 文件 ${files.length} · 按钮面规则块 ${buttonBlocks.length} · 命中 ${hits.length} · 违规 ${effective.length}`);
  if (bad.length) {
    for (const b of bad) console.error(`  · ${b}`);
  }
  for (const h of effective.slice(0, 200)) {
    console.error(
      `  ${h.file}:${h.line}  [${h.ruleId}] ${h.label}\n` +
      `      ${h.selector}${h.atStack.length ? `  {${h.atStack.join(' / ')}}` : ''}\n` +
      `      ${h.prop}: ${h.value}`,
    );
  }
  if (effective.length > 200) console.error(`  … 其余 ${effective.length - 200} 条同判据`);
  console.error('\n处置：改走扁平六态令牌（--ds-btn-*），焦点用 outline 描边环、按下用 scale/底阶；');
  console.error('确需例外（如缩略图卡片轻投影）必须写进规范文件 flat-button-spec.md 的「## 例外清单」并写明理由。');
  process.exit(1);
}

console.log(`✓ flat-button gate passed — 按钮面规则块 ${buttonBlocks.length} 个、0 违规（例外 ${hits.length} 处已在规范文件登记）`);
process.exit(0);
