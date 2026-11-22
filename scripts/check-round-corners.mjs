#!/usr/bin/env node
// check-round-corners.mjs — 「凡是画得出来的盒子都是圆角」静态闸
// （round-corner-sweep 批 2026-11-22；规范 = design-language/references/frontend/
//   focus-ring-spec.md §2.6「全面圆角标准」+ §3「静态判据」）
//
// Why a gate：round2 的 F7 只判**焦点环基座**——它把「可交互件必须圆角」钉在了键盘可达件上，
// 但作者 2026-11-22 10:17 的原话是「还是存在部分不是圆角的 得全面圆角」。屏幕上的方角
// 并不都长在焦点件上：徽章、chip、统计卡、提示条、分隔面、空态卡……它们不参与 Tab 序，
// 于是 F7 一个也看不见。人眼看到的却是「这个界面有的圆有的方」。
//
// 因此本闸把判定面从「环基座」扩到「画框元素」：任何规则只要**画出一个可见盒子**
// （背景可见 / ≥2 条可见边 / 有投影 / 有可见 outline 环），就必须给出圆角声明，或者
// 从上下文明确继承宿主圆角（`border-radius: inherit` 或同基座别处已声明）。
//
// 判据（G 系列，与规范 §2.6/§3 逐条对应）：
//
//  G1 画框必圆：画框规则自身没有 `border-radius` 声明，且查不到「同基座圆角」上下文
//     （同一规范化选择器在别处声明过圆角，或本规则声明 `inherit`）= 红。
//  G2 禁方角字面量：画框规则里 `border-radius` 为 `0` / `0px` 一律红（round1 的方角登记
//     机制已废除，没有任何「这件可以方」的出口）。
//  G3 档位只能是令牌：圆角值必须来自 `--ds-radius-*` 族（或 `inherit` / `50%`）。
//     字面量 px（`6px` 一类）视为「第二套阶梯」= 红 —— 与 F1/F2 的「单源」口径一致。
//  G4 结构豁免必须具名：真正贴边满幅的结构面（顶栏贴窗缘、全幅抽屉底、`html`/`body`/
//     `#app` 根容器）由**几何判据**放行，清单硬编码在本文件的 `STRUCTURAL_FULLBLEED`
//     里并逐条写几何理由；该清单是「结构类别」，不是 round1 那种 per-item 方角登记。
//
// 已知盲点（诚实记账，规范 §6 同款）：本闸只读 CSS 文本，不做级联计算 ——
// ① 由 JS 内联写出的圆角不在判定面；② 微格（短边 ≤12px）的 2px 档静态看不出尺寸，
// 因此 `--ds-radius-thin` 一律放行，真实几何由运行时 P-6 按**计算样式 + 实测短边**判
// （那是「这一格到底该 6px 还是 2px」的权威读数）；③ 同基座上下文判定是选择器字符串
// 近似，不做真实级联。
//
// 用法：
//   node scripts/check-round-corners.mjs              # 门禁（红/绿）
//   node scripts/check-round-corners.mjs --json       # 机器读（逐条命中）
//   node scripts/check-round-corners.mjs --inventory  # 盘点（全部画框规则 + 圆角来源）
//   node scripts/check-round-corners.mjs --root=<dir> # 在另一个检出上跑同一判据
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { resolveDesignSpec, specMissingMessage } from './lib/design-spec-source.mjs';

const ROOT_OVERRIDE = (process.argv.slice(2).find((a) => a.startsWith('--root=')) || '').slice(7);
const ROOT = ROOT_OVERRIDE
  ? fileURLToPath(new URL(`file://${ROOT_OVERRIDE}/`))
  : fileURLToPath(new URL('..', import.meta.url));
const CSS_DIR = join(ROOT, 'src', 'main', 'resources', 'web', 'css');
const SPEC_REL = 'references/frontend/focus-ring-spec.md';
const SPEC_FILE = resolveDesignSpec(SPEC_REL);
if (!SPEC_FILE) {
  console.error(`round-corners: ${specMissingMessage(SPEC_REL)}`);
  process.exit(2);
}

const JSON_OUT = process.argv.includes('--json');
const INVENTORY = process.argv.includes('--inventory');
const rel = (p) => relative(ROOT, p).replace(/\\/g, '/');

/** 规范 §2.6 的令牌族：圆角只能来自这些档位（G3）。 */
const RADIUS_TOKENS = [
  '--ds-radius-badge', '--ds-radius-chip', '--ds-radius-input', '--ds-radius-button',
  '--ds-radius-xl', '--ds-radius-2xl', '--ds-radius-card-sm', '--ds-radius-card-md',
  '--ds-radius-card-lg', '--ds-radius-shell', '--ds-radius-pill', '--ds-radius-thin',
];

/**
 * G4 结构豁免（几何判据，不是 per-item 方角登记）：
 * 只有「整幅贴边、盒边就是视口边」的结构面才可能合法方角 —— 因为它的圆角会被视口裁掉，
 * 画了也看不见。判据是「盒的边界与视口边界重合」，本清单是这些**结构类别**的静态代理。
 * 每条必须写几何理由；增删条目要说明「这个盒子的哪条边就是视口边」。
 */
const STRUCTURAL_FULLBLEED = [
  { sel: 'html', why: '文档根：盒 = 视口本身，圆角无处可画（会被视口裁掉）。' },
  { sel: 'body', why: '文档体：同上，盒 = 视口（滚动容器），四角就是窗口四角。' },
  { sel: '#app', why: '应用根容器：铺满视口、无自身可见边（背景由 body 承担）。' },
  { sel: '.app-shell', why: '外壳栅格：铺满视口的结构层，四边贴窗缘。' },
  { sel: '.flowmap-card', why: 'Flow Map 标签页容器：`flex:1` 填满标签页，四边 = 面板内壁（面板自带圆角），自身再圆会在内壁画一圈假缝。' },
  { sel: 'body.mobile-layer .fm-context-menu', why: '移动端动作表：`position:fixed; top/left/right:0` + 全屏滚动，盒边 = 视口边，圆角会被视口裁掉。' },
];

const STRUCTURAL_SELS = STRUCTURAL_FULLBLEED.map((s) => s.sel);

function stripCssComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}

/** 极简 CSS 块遍历器（含 @media/@supports/@layer 嵌套；与 check-focus-ring 同口径）。
 *  `base` 把子串偏移还原成整文件绝对偏移（供 Codemod 精确插桩）。 */
function* walkRulesDeep(src, depth = 0, base = 0) {
  const text = stripCssComments(src);
  let i = 0;
  let selStart = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === '{') {
      const prelude = text.slice(selStart, i).trim();
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
      if (prelude.startsWith('@')) {
        if (depth < 12 && /\{/.test(body)) yield* walkRulesDeep(body, depth + 1, base + bodyStart);
      } else if (prelude) {
        const line = text.slice(0, selStart).split('\n').length;
        yield { selector: prelude, body, line, nested: depth > 0, bodyStartAbs: base + bodyStart };
      }
      i = j + 1;
      selStart = i;
      continue;
    }
    if (ch === '}' || ch === ';') selStart = i + 1;
    i++;
  }
}

function parseDecls(body) {
  const out = [];
  for (const raw of body.split(';')) {
    const k = raw.indexOf(':');
    if (k < 0) continue;
    const prop = raw.slice(0, k).trim().toLowerCase();
    const value = raw.slice(k + 1).replace(/!important/gi, '').trim();
    if (!/^[-a-z]/.test(prop) || !value) continue;
    out.push({ prop, value });
  }
  return out;
}

function splitSelectorGroup(selector) {
  const out = [];
  let depth = 0;
  let cur = '';
  for (const ch of selector) {
    if (ch === '(') depth++;
    else if (ch === ')') depth--;
    if (ch === ',' && depth === 0) { out.push(cur); cur = ''; continue; }
    cur += ch;
  }
  if (cur.trim()) out.push(cur);
  return out.map((s) => s.trim()).filter(Boolean);
}

/** `:is()` / `:where()` 展开：成员各自成一条候选选择器（几何继承要靠这个判）。 */
function expandIs(sel, depth = 0) {
  if (depth > 6) return [sel];
  const m = /:(?:is|where)\(([^()]*(?:\([^()]*\)[^()]*)*)\)/.exec(sel);
  if (!m) return [sel];
  const out = [];
  for (const alt of splitSelectorGroup(m[1])) out.push(...expandIs(sel.replace(m[0], alt), depth + 1));
  return out;
}

/**
 * 规范化基座键（可能多条）：去掉伪类/伪元素/属性/状态后缀，取最后一段复合选择器，并给出
 * **复合前缀**（`#input-bar.frozen` 同时产出 `#input-bar`）—— 变体规则与态规则正是从基座
 * 规则继承几何的，不做这层展开就会把「已经圆了的状态态」误报成方角。
 */
function baseKeys(sel) {
  const keys = new Set();
  for (const variant of expandIs(sel)) {
    const last = variant.split(/\s*[>+~]\s*|\s+/).filter(Boolean).pop() || variant;
    const cleaned = last
      .replace(/::?[a-z-]+(\([^)]*\))?/gi, '')
      .replace(/\[[^\]]*\]/g, '')
      .replace(/\.(is-[a-z-]+|active|open|selected|hover|focus|shown|hidden|expanded|collapsed)/gi, '')
      .trim();
    if (!cleaned) continue;
    keys.add(cleaned);
    const parts = cleaned.match(/(#[\w-]+|\.[\w-]+)/g) || [];
    let acc = '';
    for (const part of parts) { acc += part; keys.add(acc); }
  }
  return [...keys];
}

/**
 * 基座「族」：同一元素被多条规则命中时（BEM 修饰/复合类/ID+类），圆角可能写在
 * 兄弟规则上（`.ds-btn` 给 `.ds-btn--primary` 供圆角，`.x` 给 `.x.y` 供圆角）——
 * 这是真实级联行为，本闸用**类族前缀**近似，避免把「已经圆了」误报成方角。
 */
function familyKeys(key) {
  const keys = new Set([key]);
  const classes = key.match(/\.[A-Za-z_][\w-]*/g) || [];
  for (const c of classes) {
    keys.add(c);
    const bem = c.split('--')[0];
    if (bem && bem !== c) keys.add(bem);
  }
  return [...keys];
}

const TRANSPARENT = new Set(['none', 'transparent', 'inherit', 'initial', 'unset', 'revert']);
const NONE_SHADOW = new Set(['none', 'inherit', 'initial', 'unset', 'revert']);

/**
 * 规则自报的「微格」几何：短边字面量 ≤12px。规范 §2.6 允许微格走 2px 档
 * （6px 在 11px 格上会被浏览器钳到 50%，格子变圆点、热力图语义被破）。
 * 静态只能读字面量；靠 var()/百分比声明的尺寸由运行时 P-6 按实测短边裁。
 */
function isMicroRule(decls) {
  for (const d of decls) {
    if (!/^(width|height|min-width|min-height)$/.test(d.prop)) continue;
    const m = /^([\d.]+)px$/.exec(d.value.trim());
    if (m && Number(m[1]) <= 12) return true;
  }
  return false;
}

/**
 * 不在判定面的选择器：这些「面」没有盒形可圆，或圆角由别的闸负责。
 * 逐条写理由 —— 这份清单本身也只是判据，不是「谁可以方」的登记制。
 */
const NOT_A_BOX = [
  { re: /^(from|to|-?[\d.]+%)$/i, why: '@keyframes 内的步进选择器（不是元素，画出来的盒子属于动画宿主）。' },
  { re: /::(selection|placeholder|marker|backdrop|cue|grammar-error|spelling-error)\b/, why: '伪元素里的文本级面：无盒形，圆角无处生效。' },
  { re: /:focus(-visible)?(?![\w-])/, why: '焦点规则：圆角归基座（F8 明令焦点规则不得写 border-radius），环基座由 F7/P-5 判。' },
];

function inScope(sel) {
  return !NOT_A_BOX.some((n) => n.re.test(sel));
}

/** 该规则是否画出一个可见盒子（= 圆角判据的适用面）。 */
function paintsBox(decls) {
  const hits = [];
  for (const d of decls) {
    const v = d.value.toLowerCase();
    if (d.prop === 'background' || d.prop === 'background-color') {
      if (TRANSPARENT.has(v)) continue;
      hits.push(`${d.prop}: ${d.value.slice(0, 40)}`);
    } else if (d.prop === 'box-shadow') {
      if (NONE_SHADOW.has(v)) continue;
      hits.push('box-shadow');
    } else if (d.prop === 'border' || d.prop === 'border-color' || d.prop === 'border-style'
      || d.prop === 'border-width') {
      // `border: 0` / `border-style: none` / `border-color: transparent` 都不画边；
      // 判据只认「画得出来的边」（否则裸字件的 `border: 0` 会被当成盒子）。
      if (TRANSPARENT.has(v) || /(^|\s)(0|0px|none)(\s|$)/.test(v)) continue;
      hits.push(d.prop);
    } else if (/^border-(top|right|bottom|left)$/.test(d.prop)) {
      if (TRANSPARENT.has(v) || /(^|\s)(0|0px|none)(\s|$)/.test(v)) continue;
      hits.push(d.prop);
    } else if (d.prop === 'outline') {
      if (TRANSPARENT.has(v) || /^(none|0)\b/.test(v)) continue;
      hits.push('outline');
    }
  }
  // 「≥2 条边」判据：只有单边分隔线（border-bottom 一类）不构成盒子，圆角对它无意义。
  const sideHits = hits.filter((h) => /^border-(top|right|bottom|left)$/.test(h));
  if (sideHits.length === 1 && hits.length === sideHits.length) return { box: false, hits };
  return { box: hits.length > 0, hits };
}

/** 圆角声明的档位类别（G2/G3）。 */
function radiusClass(value) {
  const v = value.trim().toLowerCase();
  if (/^(0|0px)(\s+(0|0px))*$/.test(v)) return 'square';
  if (v === 'inherit') return 'inherit';
  if (v === '50%' || /^50%\s+50%$/.test(v)) return 'circle';
  const tokens = RADIUS_TOKENS.filter((t) => v.includes(`var(${t})`));
  if (tokens.length) return tokens.includes('--ds-radius-thin') ? 'thin' : 'token';
  if (/^[\d.]+(px|rem|em)$/.test(v)) return 'literal';
  if (/var\(--/.test(v)) return 'token';
  return 'literal';
}

const cssFiles = existsSync(CSS_DIR)
  ? readdirSync(CSS_DIR).filter((f) => f.endsWith('.css')).sort().map((f) => join(CSS_DIR, f))
  : [];
if (!cssFiles.length) {
  console.error(`round-corners: css dir not found: ${CSS_DIR}`);
  process.exit(2);
}

// ── 第一遍：全树收集「基座 → 已有圆角」上下文（G1 的继承判定）────────────────
const radiusByBase = new Map();
const rules = [];
for (const file of cssFiles) {
  const raw = readFileSync(file, 'utf8');
  for (const rule of walkRulesDeep(raw)) {
    const entry = { file: rel(file), ...rule, decls: parseDecls(rule.body) };
    rules.push(entry);
    for (const sel of splitSelectorGroup(rule.selector)) {
      // 伪元素的圆角属于**覆盖层自己**，不能拿来给元素本体作证
      // （实测坑：`.glass::before{border-radius:inherit}` 会把 `.glass` 记成已圆，
      //  于是元素本体永远不需声明 —— 这正是「看起来查过了其实没查」）。
      if (/::?(before|after)\b/.test(sel)) continue;
      for (const key of baseKeys(sel)) {
        for (const d of entry.decls) {
          if (d.prop !== 'border-radius') continue;
          const cls = radiusClass(d.value);
          const prev = radiusByBase.get(key);
          // 「thin」与「token」都算有圆角；「square」记录但标记为方角（会让 G1/G2 命中）
          if (!prev || prev === 'square') radiusByBase.set(key, cls);
        }
      }
    }
  }
}

const violations = [];
const inventory = [];
/** 画框规则计数（判定面的实际大小，输出读数用）。 */
let boxRules = 0;
const structuralHits = [];

function addViolation(code, file, line, selector, detail, extra = {}) {
  violations.push({ code, file, line, selector: selector.replace(/\s+/g, ' ').trim(), detail, ...extra });
}

for (const rule of rules) {
  const { box, hits } = paintsBox(rule.decls);
  if (!box) continue;
  const sels = splitSelectorGroup(rule.selector);
  // 判定面：每条选择器都必须在范围内（组里混着伪元素/焦点件的，整条跳过——它的其它面
  // 会在各自规则里被判）。
  if (!sels.every(inScope)) continue;
  boxRules += 1;
  const radiusDecls = rule.decls.filter((d) => d.prop === 'border-radius');
  const radiusStr = radiusDecls.map((d) => d.value).join(' / ');
  const cls = radiusDecls.length ? radiusClass(radiusDecls[0].value) : null;

  if (INVENTORY) {
    inventory.push({
      file: rule.file, line: rule.line, selector: rule.selector.replace(/\s+/g, ' ').trim(),
      paints: hits, radius: radiusStr || null, radiusClass: cls,
      inheritedFrom: sels.flatMap((s) => baseKeys(s).map((k) => radiusByBase.get(k) || null)).filter(Boolean),
    });
  }

  // G4：结构豁免（几何类别，具名清单）
  const structural = sels.every((s) => STRUCTURAL_SELS.includes(s.trim()));
  if (structural) {
    structuralHits.push({ file: rule.file, line: rule.line, selector: rule.selector.trim(), why: 'G4 结构全幅' });
    continue;
  }

  if (!radiusDecls.length) {
    // G1：本规则没写圆角 —— 允许「同基座/同族别处已声明」或选择器命中结构清单
    const inherited = sels.flatMap((s) => baseKeys(s).flatMap((k) => familyKeys(k).map((kk) => radiusByBase.get(kk)))).filter(Boolean);
    const ok = inherited.some((c) => c === 'token' || c === 'thin' || c === 'circle');
    if (!ok) {
      addViolation('G1', rule.file, rule.line, rule.selector, `画框但无圆角声明（画的是：${hits.join(' + ')}）`,
        { bodyStartAbs: rule.bodyStartAbs, micro: isMicroRule(rule.decls), pseudo: /::?(before|after)\b/.test(rule.selector) });
    }
    continue;
  }

  // G2：方角字面量
  if (cls === 'square') {
    addViolation('G2', rule.file, rule.line, rule.selector, `画框规则把圆角写成方角：${radiusStr}`);
    continue;
  }
  // G3：档位必须是令牌族（inherit / 50% 放行）
  if (cls === 'literal') {
    addViolation('G3', rule.file, rule.line, rule.selector, `圆角写成字面量（第二套阶梯）：${radiusStr}`);
  }
}

if (JSON_OUT) {
  // 大输出必须走 exitCode（process.exit 会截断管道里的 stdout）。
  console.log(JSON.stringify({
    ok: violations.length === 0, violations, structural: structuralHits.length,
    scanned: { files: cssFiles.length, rules: rules.length, boxRules },
  }, null, 2));
  process.exitCode = violations.length ? 1 : 0;
} else if (INVENTORY) {
  for (const row of inventory) {
    const state = row.radius ? `${row.radiusClass}:${row.radius}` : (row.inheritedFrom.length ? `inherit:${row.inheritedFrom[0]}` : 'MISSING');
    console.log(`${row.file}:${row.line}\t${row.selector}\t[${row.paints.join('+')}]\t${state}`);
  }
  console.log(`\n画框规则 ${inventory.length} 条 · 结构豁免 ${structuralHits.length} 条 · 红 ${violations.length} 条`);
  process.exitCode = violations.length ? 1 : 0;
} else if (violations.length) {
  const byCode = new Map();
  for (const v of violations) byCode.set(v.code, [...(byCode.get(v.code) || []), v]);
  for (const [code, list] of [...byCode].sort()) {
    console.log(`\n✗ ${code}（${list.length} 条）`);
    for (const v of list.slice(0, 200)) console.log(`  ${v.file}:${v.line} ${v.selector} — ${v.detail}`);
    if (list.length > 200) console.log(`  … 另有 ${list.length - 200} 条（--json 看全量）`);
  }
  console.log(`\nround-corners: FAIL — ${violations.length} 条（画框规则 ${boxRules} 个 / 全树 ${rules.length} 条规则 / ${cssFiles.length} 个文件；规范 §2.6「全面圆角」）`);
  process.exitCode = 1;
} else {
  console.log(`round-corners: OK — 画框规则 ${boxRules} 个全部有圆角（全树 ${rules.length} 条规则 / ${cssFiles.length} 个 CSS 文件；结构全幅豁免 ${structuralHits.length} 条命中，清单 ${STRUCTURAL_FULLBLEED.length} 项）`);
  process.exitCode = 0;
}
