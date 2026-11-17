#!/usr/bin/env node
// check-design-tokens.mjs — 设计令牌「字面量 → 令牌」静态哨兵
// （design-system-token-batch 2026-11-02；判据来源 = design-language 规范
//  SKILL.md §9 整改判例「以下为从现状里清出的跑偏值……再次出现 = 回归」
//  + §10 使用流「客户端同名字 token 直接 var() 引用，禁复制字面量」）。
//
// Why a static gate：规范表（圆角 8 档 / 字号 5 档 / 4px 栅格）原本只存在于文档，
// 实测产品 CSS 里 border-radius 28 种取值、font-size 26 种取值（含 9px/8px 等
// 规范明令「不存在合法场景」的档）—— 靠人记必然跑偏，且下一面加入时不会有任何
// 东西变红。本脚本把「表里有值取表里的」变成机器判据。
//
// ── 两档判据 ───────────────────────────────────────────────────────────
// ① 存量护栏（全仓面，允许豁免靠**基线文件**）
//     scripts/design-token-baseline.json 记录每个 css/js 文件的字面量计数。
//     判据：某文件的计数 **超过** 基线 ⇒ 红（新增了字面量）；基线里没有的
//     新文件 ⇒ 一个字面量都不许有。存量不追溯（2471 处字面量不可能一次收敛，
//     视觉回归面不可控），但**只能减不能增**——这正是「再次出现 = 回归」。
//
// ② 严格面（必须零字面量，无豁免）
//     STRICT_SCOPES 内的选择器块（设置页结构面 + 设计体系层自身）：
//     · 颜色：一个裸 #hex / rgb() / hsl() 都不许有（必须走 var(--ds-* 或既有 token)）；
//     · 结构量：font-size 必须 var() 或落 5 档阶梯；border-radius 必须 var() 或落
//       8 档阶梯；transition 禁 `all`。
//     这些块是本批已整改面 —— 严格面红了就是整改被回退。
//
// ── 用法 ──────────────────────────────────────────────────────────────
//   node scripts/check-design-tokens.mjs                 # 门禁（红/绿）
//   node scripts/check-design-tokens.mjs --json          # 机器读
//   node scripts/check-design-tokens.mjs --update-baseline  # 重生成基线（仅允许
//                                                       # 在「确实把数字降下来」的
//                                                       # 合并里用；升基线 = 放水）
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parse } from 'acorn';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const CSS_DIR = join(ROOT, 'src', 'main', 'resources', 'web', 'css');
const JS_DIR = join(ROOT, 'src', 'main', 'resources', 'web', 'js');
const BASELINE_PATH = join(ROOT, 'scripts', 'design-token-baseline.json');
const ARGS = new Set(process.argv.slice(2));

// ── 规范表（与 design-system.css / SKILL.md 同步；改表先改规范再改这里）──
const FS_LADDER = new Set(['11px', '12px', '13px', '15px', '20px']);
const RADIUS_LADDER = new Set(['0', '4px', '6px', '8px', '10px', '14px', '16px', '20px', '999px', '50%']);
/** 11px 以下不存在合法场景（SKILL.md §2），单列成绝对判据（新面不许出现）。 */
const FS_MIN = 11;

/** 严格面：选择器含这些前缀的块，必须零字面量。 */
const STRICT_SELECTORS = [
  '.settings-section',
  '.settings-row',
  '.settings-label',
  '.settings-collapse',
  '.settings-drill',
  '.ds-',        // 设计体系组件类层
  '#settings-content',
];
/** 严格面文件：整文件必须零裸色值（令牌出生地除外 —— 本文件只出生 --ds-* 结构量，
 *  颜色一律走既有 token 别名，所以整文件零色值是可达的）。 */
const STRICT_FILES = ['design-system.css'];

const COLOR_RE = /#[0-9a-fA-F]{3,8}\b|\brgba?\([^)]*\)|\bhsla?\([^)]*\)/g;
const COMMENT_RE = /\/\*[\s\S]*?\*\//g;

/**
 * CSS 语义关键字白名单 —— 这类值的语义是「随父 / 继承 / 回退 / 借当前色」，
 * **不携带任何可量化数值**，因此在 8 档圆角、5 档字号、4px 栅格这些**数值阶梯**
 * 面前不成立「档外值」。
 *
 * 批次 2026-11-03：`border-radius: inherit` 被判定为档外字面量 = **假阳性类别** ——
 * 它与 `#ff00aa` / `border-radius: 7px` 那种真违规不同类：后者是可量化值（能落档 /
 * 不能落档），前者只是一条继承指令。
 *
 * 纪律：**具名白名单 + 单点判定**（见 isSemanticKeywordValue），**不用正则撒网** ——
 * /inherit/ 之类会顺手放过 `inherit-foo`、`uninherited` 这种真值。
 */
const CSS_SEMANTIC_KEYWORDS = new Set([
  'inherit', 'initial', 'unset', 'revert', 'revert-layer', 'currentcolor',
]);

/**
 * 整条声明值是否只由语义关键字构成（**唯一判定点**：所有「档外值」类哨兵 ——
 * 圆角 / 字号 / 将来的 shadow / spacing —— 都必须先过这里再谈档位）。
 * 多 token 值（`inherit inherit`）同样算语义值；`7px inherit` 这种混入数值的
 * 值**不算**（仍走档位判定，宁可漏放不可放过真值）。
 * @param {string} raw 属性值原文（可含多 token）
 * @returns {boolean}
 */
function isSemanticKeywordValue(raw) {
  const tokens = String(raw).trim().split(/\s+/).filter(Boolean);
  if (tokens.length === 0) return false;
  return tokens.every((t) => CSS_SEMANTIC_KEYWORDS.has(t.toLowerCase()));
}

/** 语义关键字命中记录（informational：只打印，不参与基线比较、不计入 radiusOff）。 */
const semanticHits = {};   // rel → { radius: {n, keywords:Set}, fs: {n, keywords:Set} }
function noteSemantic(rel, kind, token) {
  const box = (semanticHits[rel] ||= {});
  const slot = (box[kind] ||= { n: 0, keywords: new Set() });
  slot.n += 1;
  slot.keywords.add(token.toLowerCase());
}

/**
 * 一行里真正的「颜色字面量」——**排除**由 var() 拼装的色值
 * （`rgb(var(--brand-green) / 0.25)` 是令牌组合，不是字面量；后者才是要禁的）。
 * @param {string} line
 * @returns {string[]}
 */
function colorLiterals(line) {
  const out = [];
  for (const m of line.matchAll(/#[0-9a-fA-F]{3,8}\b/g)) out.push(m[0]);
  for (const m of line.matchAll(/\brgba?\(([^)]*)\)/g)) { if (!m[1].includes('var(')) out.push(`rgba(${m[1]})`); }
  for (const m of line.matchAll(/\bhsla?\(([^)]*)\)/g)) { if (!m[1].includes('var(')) out.push(`hsl(${m[1]})`); }
  return out;
}

const fails = [];
const notes = [];
const add = (file, line, detail, rule) => fails.push({ file, line, detail, rule });

function walk(dir, acc = []) {
  if (!existsSync(dir)) return acc;
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, acc);
    else if (['.css', '.js', '.mjs'].includes(extname(p))) acc.push(p);
  }
  return acc;
}

/** 去掉注释但**保留行号**（把注释内容换成等长空白/换行）。 */
function stripCommentsKeepLines(src) {
  return src.replace(COMMENT_RE, (m) => m.replace(/[^\n]/g, ' '));
}

function cssComments(src, offset) {
  const comments = [];
  let quote;
  for (let i = 0; i < src.length; i++) {
    if (src[i] === '\\') { i++; continue; }
    if (quote) {
      if (src[i] === quote) quote = undefined;
    } else if (src[i] === '"' || src[i] === "'") {
      quote = src[i];
    } else if (src.startsWith('/*', i)) {
      const end = src.indexOf('*/', i + 2);
      if (end < 0) break;
      comments.push({ start: offset + i, end: offset + end + 2 });
      i = end + 1;
    }
  }
  return comments;
}

function stripJsCommentsKeepLines(src) {
  const comments = [];
  const ast = parse(src, { ecmaVersion: 'latest', sourceType: 'module', allowHashBang: true, onComment: comments });
  const visit = (node, ancestors = []) => {
    if (!node || typeof node !== 'object') return;
    if (node.type === 'TemplateLiteral' && node.expressions.length === 0) {
      const quasi = node.quasis[0];
      const value = src.slice(quasi.start, quasi.end);
      const parent = ancestors.at(-1);
      const block = ancestors.findLast(ancestor => ancestor.type === 'BlockStatement');
      const target = parent?.type === 'AssignmentExpression' && parent.operator === '=' && parent.right === node
        ? parent.left : undefined;
      const styleTarget = target?.type === 'MemberExpression' && !target.computed && target.property.name === 'textContent' &&
        target.object.type === 'Identifier' && block?.body.some(statement => statement.type === 'VariableDeclaration' &&
          statement.declarations.some(declaration => declaration.id.type === 'Identifier' && declaration.id.name === target.object.name &&
            declaration.init?.type === 'CallExpression' && declaration.init.callee.type === 'MemberExpression' &&
            !declaration.init.callee.computed && declaration.init.callee.object.name === 'document' &&
            declaration.init.callee.property.name === 'createElement' && declaration.init.arguments[0]?.value === 'style'));
      // Only known CSS payloads have CSS comments; ordinary JS strings retain their bytes.
      if (styleTarget || /^\s*<style(?:\s[^>]*)?>[\s\S]*<\/style>\s*$/i.test(value)) {
        comments.push(...cssComments(value, quasi.start));
      }
    }
    for (const value of Object.values(node)) {
      if (Array.isArray(value)) value.forEach(child => visit(child, [...ancestors, node]));
      else if (value && typeof value === 'object') visit(value, [...ancestors, node]);
    }
  };
  visit(ast);
  comments.sort((a, b) => a.start - b.start);
  let result = '';
  let pos = 0;
  for (const { start, end } of comments) {
    result += src.slice(pos, start) + src.slice(start, end).replace(/[^\r\n\u2028\u2029]/g, ' ');
    pos = end;
  }
  return result + src.slice(pos);
}

/** 判断某一行是否落在「自定义属性声明」里（`--x: …`）——令牌出生地不算消费。 */
function isTokenDeclLine(line) {
  return /^\s*--[a-z0-9-]+\s*:/i.test(line);
}

/** 取一个 css 文件的选择器块：返回 [{selector, startLine, bodyLines:[{line,text}]}]。 */
function parseBlocks(lines) {
  const blocks = [];
  let cur = null;
  let depth = 0;
  let selector = '';
  let startLine = 0;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (depth === 0) {
      if (line.includes('{')) {
        const idx = line.indexOf('{');
        selector = (selector + ' ' + line.slice(0, idx)).trim();
        startLine = i + 1;
        cur = { selector: selector.replace(/\s+/g, ' '), startLine, bodyLines: [] };
        depth = 1;
        const rest = line.slice(idx + 1);
        if (rest.trim()) cur.bodyLines.push({ line: i + 1, text: rest });
        if (rest.includes('}')) { blocks.push(cur); cur = null; depth = 0; selector = ''; }
      } else {
        selector = (selector + ' ' + line).trim();
        if (selector.length > 400) selector = '';
      }
    } else {
      const close = line.indexOf('}');
      if (close >= 0) {
        const head = line.slice(0, close);
        if (head.trim()) cur.bodyLines.push({ line: i + 1, text: head });
        blocks.push(cur);
        cur = null;
        depth = 0;
        selector = line.slice(close + 1);
      } else {
        cur.bodyLines.push({ line: i + 1, text: line });
      }
    }
  }
  if (cur) blocks.push(cur);
  return blocks;
}

// ── ① 存量护栏：逐文件计数 ────────────────────────────────────────────
const files = [...walk(CSS_DIR), ...walk(JS_DIR)];
const counts = {};   // rel → {color, transitionAll, fsSmall, radiusOff}

for (const abs of files) {
  const rel = relative(join(ROOT, 'src', 'main', 'resources', 'web'), abs).replaceAll('\\', '/');
  const isCss = extname(abs) === '.css';
  const raw = readFileSync(abs, 'utf8');
  const src = isCss ? stripCommentsKeepLines(raw) : stripJsCommentsKeepLines(raw);
  const lines = src.split('\n');
  const c = { color: 0, transitionAll: 0, fsSmall: 0, radiusOff: 0 };
  lines.forEach((line) => {
    // 令牌出生行不算消费：`--x: #fff` 是单源定义（全站唯一的合法字面量），
    // 门禁要抓的是**消费点的裸值**。JS 文件无令牌概念，一律计入。
    if (isCss && isTokenDeclLine(line)) return;
    c.color += colorLiterals(line).length;
    if (/\btransition\s*:\s*all\b/.test(line)) c.transitionAll += 1;
    if (!isCss) return;
    const fs = line.match(/font-size\s*:\s*([^;}]+)/);
    if (fs) {
      const raw = fs[1].trim();
      const v = raw.split(/\s+/)[0];
      // 语义关键字（inherit/unset/…）不携带数值，不成立「低于 11px」（历史上这条
      // 判据因 px 形态判据已天然免疫，此处显式走同一单点判定，防将来改判据时回退）。
      if (isSemanticKeywordValue(raw)) noteSemantic(rel, 'fs', v);
      else if (!v.startsWith('var(') && /^[\d.]+px$/.test(v) && parseFloat(v) < FS_MIN) c.fsSmall += 1;
    }
    const br = line.match(/border-radius\s*:\s*([^;}]+)/);
    if (br) {
      const raw = br[1].trim();
      const first = raw.split(/\s+/)[0];
      if (isSemanticKeywordValue(raw)) noteSemantic(rel, 'radius', first);
      else if (!first.startsWith('var(') && !RADIUS_LADDER.has(first)) c.radiusOff += 1;
    }
  });
  if (c.color || c.transitionAll || c.fsSmall || c.radiusOff) counts[rel] = c;
}

if (ARGS.has('--update-baseline')) {
  const payload = {
    _comment: 'check-design-tokens.mjs 存量基线（设计令牌批次 2026-11-02）。数字只许降不许升：'
      + '升基线 = 放水，必须在批报告里逐项说明理由。重生成：node scripts/check-design-tokens.mjs --update-baseline',
    generatedAt: new Date().toISOString(),
    counts,
  };
  writeFileSync(BASELINE_PATH, JSON.stringify(payload, null, 2) + '\n');
  const total = Object.values(counts).reduce((s, c) => s + c.color, 0);
  console.log(`基线已写入 ${relative(ROOT, BASELINE_PATH)}（${Object.keys(counts).length} 文件 / ${total} 处色值字面量）`);
  process.exit(0);
}

const baseline = existsSync(BASELINE_PATH)
  ? JSON.parse(readFileSync(BASELINE_PATH, 'utf8')).counts
  : {};

for (const [rel, c] of Object.entries(counts)) {
  const base = baseline[rel];
  if (!base) {
    add(rel, 0, `新文件出现设计字面量（${JSON.stringify(c)}）——新面一律走 var(--ds-*) / 既有 token`, 'new-file-literals');
    continue;
  }
  for (const key of ['color', 'transitionAll', 'fsSmall', 'radiusOff']) {
    const now = c[key] || 0;
    const was = base[key] || 0;
    if (now > was) {
      const what = key === 'color' ? '颜色字面量'
        : key === 'transitionAll' ? 'transition: all'
          : key === 'fsSmall' ? '低于 11px 的字号' : '圆角档外值';
      add(rel, 0, `${what} 从基线 ${was} 增到 ${now}（只许减不许增）`, `baseline-${key}`);
    } else if (now < was) {
      notes.push(`${rel}: ${key} ${was} → ${now}（改善，建议下次 --update-baseline 收紧）`);
    }
  }
}

// ── ② 严格面：设置页结构面 + 设计体系层 ────────────────────────────────
for (const abs of walk(CSS_DIR)) {
  const rel = relative(join(ROOT, 'src', 'main', 'resources', 'web'), abs).replaceAll('\\', '/');
  const src = stripCommentsKeepLines(readFileSync(abs, 'utf8'));
  const lines = src.split('\n');
  const isStrictFile = STRICT_FILES.includes(rel);

  if (isStrictFile) {
    lines.forEach((line, i) => {
      // 令牌出生行放行（`--ds-x: <黑α墨常量>`）：令牌层是出生地，消费行才必须走
      // var()。判据边界 = 行首是 `--ds-` 声明；任何选择器体内的裸色值仍报红。
      if (/^\s*--ds-[a-z0-9-]+\s*:/i.test(line)) return;
      const hits = colorLiterals(line);
      if (hits.length) add(rel, i + 1, `严格面文件出现裸色值 ${hits.join(' ')}——走既有 token 别名`, 'strict-file-color');
    });
  }

  for (const block of parseBlocks(lines)) {
    const sel = block.selector;
    if (!sel || !STRICT_SELECTORS.some(p => sel.includes(p))) continue;
    for (const { line, text } of block.bodyLines) {
      const hits = colorLiterals(text);
      if (hits.length) add(rel, line, `严格面 [${sel.slice(0, 48)}] 裸色值 ${hits.join(' ')}——改走 var(--ds-*)`, 'strict-color');
      if (isTokenDeclLine(text)) continue;
      const fs = text.match(/font-size\s*:\s*([^;}]+)/);
      if (fs) {
        const raw = fs[1].trim();
        const v = raw.split(/\s+/)[0];
        if (isSemanticKeywordValue(raw)) noteSemantic(rel, 'fs', v);
        else if (!v.startsWith('var(') && !FS_LADDER.has(v)) add(rel, line, `严格面字号 ${v} 不在阶梯（11/12/13/15/20）`, 'strict-fs');
      }
      const br = text.match(/border-radius\s*:\s*([^;}]+)/);
      if (br) {
        const raw = br[1].trim();
        const v = raw.split(/\s+/)[0];
        if (isSemanticKeywordValue(raw)) noteSemantic(rel, 'radius', v);
        else if (!v.startsWith('var(') && !RADIUS_LADDER.has(v)) add(rel, line, `严格面圆角 ${v} 不在 8 档（4/6/8/10/14/16/20/999）`, 'strict-radius');
      }
      if (/\btransition\s*:\s*all\b/.test(text)) add(rel, line, '严格面 transition: all（禁 all，逐属性列出）', 'strict-transition');
    }
  }
}

// ── ③ 死引用：`var(--ds-x)` 必须有定义 ────────────────────────────────
const dsDefs = new Set();
const dsUsers = [];
for (const abs of walk(CSS_DIR)) {
  const rel = relative(join(ROOT, 'src', 'main', 'resources', 'web'), abs).replaceAll('\\', '/');
  const src = stripCommentsKeepLines(readFileSync(abs, 'utf8'));
  src.split('\n').forEach((line, i) => {
    for (const m of line.matchAll(/(--ds-[a-z0-9-]+)\s*:/gi)) dsDefs.add(m[1]);
    for (const m of line.matchAll(/var\(\s*(--ds-[a-z0-9-]+)/gi)) dsUsers.push({ rel, line: i + 1, tok: m[1] });
  });
}
if (dsDefs.size === 0) {
  add('src/main/resources/web/css/design-system.css', 1, '找不到任何 --ds-* 定义——令牌层缺失', 'ds-missing');
} else {
  for (const u of dsUsers) {
    if (!dsDefs.has(u.tok)) add(u.rel, u.line, `引用未定义的令牌 ${u.tok}`, 'ds-dead-var');
  }
}

// ── 报告 ─────────────────────────────────────────────────────────────
const totalColor = Object.values(counts).reduce((s, c) => s + c.color, 0);
if (ARGS.has('--json')) {
  const semantic = Object.fromEntries(Object.entries(semanticHits).map(([rel, box]) => [rel, Object.fromEntries(Object.entries(box).map(([k, v]) => [k, { n: v.n, keywords: [...v.keywords] }]))]));
  console.log(JSON.stringify({ ok: fails.length === 0, totalColor, baselineColor: Object.values(baseline).reduce((s, c) => s + (c.color || 0), 0), fails, notes, semanticKeywords: semantic }, null, 2));
} else {
  console.log(`design-tokens：${Object.keys(counts).length} 文件 / ${totalColor} 处颜色字面量 / ${Object.keys(baseline).length} 个基线条目`);
  const semTotal = Object.values(semanticHits).reduce((s, box) => s + Object.values(box).reduce((t, v) => t + v.n, 0), 0);
  if (semTotal > 0) {
    for (const [rel, box] of Object.entries(semanticHits)) {
      const parts = Object.entries(box).map(([k, v]) => `${k}×${v.n}(${[...v.keywords].join('/')})`);
      console.log(`  ℹ ${rel}: 语义关键字（不计档外） ${parts.join(' ')}`);
    }
  }
  for (const n of notes) console.log(`  ↑ ${n}`);
  if (fails.length === 0) {
    console.log('PASS: 无新增设计字面量；严格面（设置页结构面 + 设计体系层）零跑偏，令牌引用全部有定义。');
  } else {
    console.log(`\nFAIL ${fails.length} 项：`);
    for (const f of fails) console.log(`  ${f.file}:${f.line}  [${f.rule}] ${f.detail}`);
  }
}
process.exit(fails.length ? 1 : 0);
