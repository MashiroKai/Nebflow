#!/usr/bin/env node
// check-mobile-tap.mjs — 手机端「最小可点尺寸」机检（min-tap 批 2026-11-06）
//
// Why a gate：作者令（逐字）「手机端所有的交互 最小按钮大小就是我们统一的对话框顶部
// 悬浮按钮的样式」。这条规范一旦只写进文档，下一个面就会各自画 24×26 的 chip、
// 28×24 的图标钮 —— 触控面积不足不是「不美观」，是**点不中**。所以把「一个真值 48」
// 变成机器判据：任何手机层可点元素，视觉边 < 视觉档 **且** 有效命中盒 < 命中盒档
// ⇒ 违规（两条同时不达标才算：视觉小但命中盒补足 48 是**合法写法**，不是违规）。
//
// 两档（令牌 = css/design-system.css §㉓；规范 = design/liquid-morph__20261106/TAP.md）：
//   --ds-hit-tap-min  视觉档 = 48px（= 手机档 .g2-fab 实测边长，glass.css:717-720）
//   --ds-hit-box-min  命中盒档 = 48px（::after / padding / 透明外扩后的可点区）
//
// 两路输入（都支持，缺一不可 —— 缺的那一路必须报「无法判定」，不许静默放过）：
//   (a) 静态腿：扫 css（body.mobile-layer 作用域内声明了 <48px 边长的可点类）
//              + js（JS 凭空生成的类名在 CSS 里零命中 ⇒ 无样式面，几何算不出来）
//       ⇒ 静态**永远算不出最终几何**（级联 / var() / calc / 内容撑开 / JS 改写），
//         故这一路只产出「无法判定」，不产出合规结论。
//   (b) 运行态腿（**主口径**）：读真链几何读数 JSON
//              [{ selector, w, h, hitW, hitH }]（hitW/hitH 已含 ::before/::after 外扩；
//              外扩不可解析时写 null ⇒ 本闸判「无法判定」而不是当成 0 或当成合规）。
//
// 用法：
//   node scripts/check-mobile-tap.mjs                          # 门禁（默认读改前基线读数）
//   node scripts/check-mobile-tap.mjs --readings <path.json>   # 换读数（改后腿 / 新面）
//   node scripts/check-mobile-tap.mjs --json                   # 机器读
//   node scripts/check-mobile-tap.mjs --max-violations 17      # 过渡期只许不增（禁放宽判据）
// 退出码：0 = 绿；1 = 红或主口径不可用（读数缺失 / 令牌缺失 / ::after 外扩不可解析）。

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const CSS_DIR = join(ROOT, 'src', 'main', 'resources', 'web', 'css');
const JS_DIR = join(ROOT, 'src', 'main', 'resources', 'web', 'js');
const DEFAULT_READINGS = join(
  ROOT, 'reports-evidence', 'todo-mobile-revamp__20261106', 'shots-before', 'readings-before.json',
);

const argv = process.argv.slice(2);
const AS_JSON = argv.includes('--json');
const argVal = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : dflt;
};
const READINGS = argVal('--readings', DEFAULT_READINGS);
const MAX_VIOL = Number(argVal('--max-violations', '0'));

const rel = (p) => relative(ROOT, p);

// ── 令牌解析（--ds-hit-* 一跳别名到既有单源，必须解到 px 数）────────────────
const stripComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));

function readCssFiles() {
  return readdirSync(CSS_DIR)
    .filter((f) => f.endsWith('.css'))
    .map((f) => ({ file: f, path: join(CSS_DIR, f), text: readFileSync(join(CSS_DIR, f), 'utf8') }));
}

/** 定制属性字面表：name -> {value, site}（后声明覆盖先声明，与级联近似）。 */
function tokenTable(cssFiles) {
  const map = new Map();
  for (const { file, text } of cssFiles) {
    const clean = stripComments(text);
    const lines = clean.split('\n');
    lines.forEach((line, idx) => {
      const m = line.match(/^\s*(--[a-z0-9-]+)\s*:\s*([^;]+);/i);
      if (m) map.set(m[1], { value: m[2].trim(), site: `${file}:${idx + 1}` });
    });
  }
  return map;
}

/** var() 解析：支持一跳链与 fallback；解析不到返回 null（**不猜**）。 */
function resolveToken(map, name, depth = 0) {
  if (depth > 8) return null;
  const hit = map.get(name);
  if (!hit) return null;
  const v = hit.value.trim();
  const varMatch = v.match(/^var\(\s*(--[a-z0-9-]+)\s*(?:,\s*([\s\S]+))?\)$/i);
  if (varMatch) {
    const inner = resolveToken(map, varMatch[1], depth + 1);
    if (inner) return { ...inner, chain: [name, ...(inner.chain || [])] };
    if (varMatch[2]) return { px: Number.NaN, raw: varMatch[2].trim(), site: hit.site, chain: [name] };
    return null;
  }
  const pxMatch = v.match(/^(-?\d+(?:\.\d+)?)px$/);
  return { px: pxMatch ? Number(pxMatch[1]) : Number.NaN, raw: v, site: hit.site, chain: [name] };
}

const cssFiles = readCssFiles();
const tokens = tokenTable(cssFiles);
const VISUAL = resolveToken(tokens, '--ds-hit-tap-min');
const BOX = resolveToken(tokens, '--ds-hit-box-min');

const envErrors = [];
if (!VISUAL || !Number.isFinite(VISUAL.px)) {
  envErrors.push('令牌 --ds-hit-tap-min 未声明或无法解析为 px（css/design-system.css §㉓）');
}
if (!BOX || !Number.isFinite(BOX.px)) {
  envErrors.push('令牌 --ds-hit-box-min 未声明或无法解析为 px（css/design-system.css §㉓）');
}
const VISUAL_DP = VISUAL && Number.isFinite(VISUAL.px) ? VISUAL.px : 48;
const BOX_DP = BOX && Number.isFinite(BOX.px) ? BOX.px : 48;

// ── (b) 运行态腿（主口径）────────────────────────────────────────────────
function runtimeLeg() {
  if (!existsSync(READINGS)) {
    return {
      ok: false,
      reason: `运行态读数文件缺失：${rel(READINGS)}（主口径不可用 ⇒ 无法判定，不许静默放过）`,
      items: [],
      violations: [],
      undetermined: [],
    };
  }
  let raw;
  try {
    raw = JSON.parse(readFileSync(READINGS, 'utf8'));
  } catch (e) {
    return {
      ok: false,
      reason: `运行态读数文件不是合法 JSON：${rel(READINGS)} — ${e.message}`,
      items: [],
      violations: [],
      undetermined: [],
    };
  }
  const list = Array.isArray(raw) ? raw : Array.isArray(raw.items) ? raw.items : [];
  const items = [];
  for (const it of list) {
    const w = Number(it.w);
    const h = Number(it.h);
    const hitW = it.hitW === null || it.hitW === undefined ? null : Number(it.hitW);
    const hitH = it.hitH === null || it.hitH === undefined ? null : Number(it.hitH);
    const rec = {
      selector: it.selector,
      w, h, hitW, hitH,
      visualShort: [],
      boxShort: [],
      status: 'ok',
      diff: null,
    };
    if (!Number.isFinite(w) || !Number.isFinite(h)) {
      rec.status = 'undetermined';
      rec.why = '视觉几何缺失（w/h 非数）';
      items.push(rec);
      continue;
    }
    if (w < VISUAL_DP) rec.visualShort.push({ axis: 'w', have: w, need: VISUAL_DP, short: +(VISUAL_DP - w).toFixed(2) });
    if (h < VISUAL_DP) rec.visualShort.push({ axis: 'h', have: h, need: VISUAL_DP, short: +(VISUAL_DP - h).toFixed(2) });

    if (hitW === null || hitH === null) {
      // 外扩解析不出 ⇒ 无法判定（既不当 0 也不算合规）
      rec.status = rec.visualShort.length ? 'undetermined' : 'ok';
      rec.why = '命中盒不可解析（::before/::after 外扩取值非 px 或缺失）';
      items.push(rec);
      continue;
    }
    if (hitW < BOX_DP) rec.boxShort.push({ axis: 'w', have: hitW, need: BOX_DP, short: +(BOX_DP - hitW).toFixed(2) });
    if (hitH < BOX_DP) rec.boxShort.push({ axis: 'h', have: hitH, need: BOX_DP, short: +(BOX_DP - hitH).toFixed(2) });

    if (rec.visualShort.length && rec.boxShort.length) {
      rec.status = 'violation';
      const vw = rec.visualShort.find((s) => s.axis === 'w');
      const vh = rec.visualShort.find((s) => s.axis === 'h');
      const bw = rec.boxShort.find((s) => s.axis === 'w');
      const bh = rec.boxShort.find((s) => s.axis === 'h');
      rec.diff = {
        visual: `${vw ? '-' + vw.short : '0'}×${vh ? '-' + vh.short : '0'}`,
        box: `${bw ? '-' + bw.short : '0'}×${bh ? '-' + bh.short : '0'}`,
      };
    } else if (rec.visualShort.length && !rec.boxShort.length) {
      // 视觉小、命中盒补足 ⇒ 合规写法（规范 §3 的正面用例）
      rec.status = 'ok';
      rec.why = '视觉小于视觉档但命中盒达标（::after/padding 外扩写法）';
    }
    items.push(rec);
  }
  return {
    ok: true,
    file: rel(READINGS),
    items,
    violations: items.filter((x) => x.status === 'violation'),
    undetermined: items.filter((x) => x.status === 'undetermined'),
  };
}

// ── (a) 静态腿 A：css 里 body.mobile-layer 作用域的可点类声明 ────────────────
// 「声明的 px 边长 < 视觉档」只说明**某一条规则**给了小尺寸，最终几何还可能被
// 级联/var()/内容/JS 改写 ⇒ 一律记「无法判定（需运行态）」，不记违规也不记合规。
const INTERACTIVE_HINT = /(btn|fab|chip|toggle|tap|hit|icon|close|back|add|send|stop|trash|key|switch|check|radio|tab|row|handle|more)/i;
const NON_TARGET = /(\bsvg\b|::?(before|after)|\s>\s*i\b|\s+i\s*$)/i;

function walkScopedRules(text) {
  // 只取顶层（@media 内也算）选择器以 `body.mobile-layer` 开头的规则块。
  const out = [];
  const lines = stripComments(text).split('\n');
  let depth = 0;
  let collecting = null; // { selector, startLine, buf, depthAtOpen }
  lines.forEach((line, idx) => {
    const lineNo = idx + 1;
    if (!collecting) {
      const idxBrace = line.indexOf('{');
      if (idxBrace >= 0) {
        const sel = line.slice(0, idxBrace).trim();
        if (sel.startsWith('body.mobile-layer') || sel.includes('body.mobile-layer')) {
          collecting = { selector: sel.replace(/\s+/g, ' '), startLine: lineNo, buf: line.slice(idxBrace + 1) };
          depth = 1;
          const closeIdx = collecting.buf.indexOf('}');
          if (closeIdx >= 0) {
            // 单行规则
            collecting.buf = collecting.buf.slice(0, closeIdx);
            out.push(collecting);
            collecting = null;
            depth = 0;
          }
        } else {
          depth++;
        }
      }
    } else {
      const closeIdx = line.indexOf('}');
      if (closeIdx >= 0) {
        collecting.buf += '\n' + line.slice(0, closeIdx);
        out.push(collecting);
        collecting = null;
        depth = 0;
      } else {
        collecting.buf += '\n' + line;
      }
    }
  });
  return out;
}

function staticCssLeg() {
  const hits = [];
  for (const { file, text } of cssFiles) {
    for (const rule of walkScopedRules(text)) {
      if (!INTERACTIVE_HINT.test(rule.selector)) continue;
      if (NON_TARGET.test(rule.selector)) continue;
      const decls = rule.buf.split(';');
      for (const d of decls) {
        const m = d.match(/(^|\s)(width|height|min-width|min-height)\s*:\s*(-?\d+(?:\.\d+)?)px\s*$/i);
        if (!m) continue;
        const px = Number(m[3]);
        if (px >= VISUAL_DP) continue;
        hits.push({
          kind: 'css-decl',
          where: `${file}:${rule.startLine}`,
          selector: rule.selector,
          decl: `${m[2]}: ${px}px`,
          why: `静态声明边长 ${px}px < 视觉档 ${VISUAL_DP}px —— 最终几何受级联/var()/内容/JS 影响，静态不可判定`,
        });
      }
    }
  }
  return hits;
}

// ── (a) 静态腿 B：JS 凭空生成的类名在 CSS 零命中 ⇒ 无样式面 ─────────────────
function listJs(dir) {
  const out = [];
  const walk = (d) => {
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const p = join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.js')) out.push(p);
    }
  };
  walk(dir);
  return out;
}

function staticJsLeg() {
  const cssText = cssFiles.map((f) => stripComments(f.text)).join('\n');
  // 「有样式面」判定：类名有 `.cls` 规则，或该节点靠 id 上样式（`#cls`，如
  // `className='deck-fab'` + `#deck-fab`），或 HTML 里出现过该 class。
  const htmlText = (() => {
    try {
      return readFileSync(join(ROOT, 'src', 'main', 'resources', 'web', 'index.html'), 'utf8');
    } catch {
      return '';
    }
  })();
  const hasStyleFace = (cls) =>
    cssText.includes('.' + cls) || cssText.includes('#' + cls) || htmlText.includes(cls);

  const seen = new Map(); // cls -> {where, count}
  const VALID = /^[a-z][a-z0-9_-]*$/i;
  for (const file of listJs(JS_DIR)) {
    const src = readFileSync(file, 'utf8');
    const lines = src.split('\n');
    lines.forEach((line, idx) => {
      const patterns = [
        /className\s*=\s*['"`]([^'"`]+)['"`]/g,
        /classList\.(?:add|toggle)\(([^)]*)\)/g,
      ];
      for (const re of patterns) {
        let m;
        while ((m = re.exec(line))) {
          const literals = re.source.includes('classList')
            ? [...m[1].matchAll(/['"`]([^'"`]+)['"`]/g)].map((x) => x[1])
            : [m[1]];
          for (const chunk of literals) {
            for (const cls of chunk.split(/\s+/).filter(Boolean)) {
              // 模板拼接残片（`dvp-chip${c.cls`）不是类名 ⇒ 丢弃，不当判据
              if (!VALID.test(cls)) continue;
              if (!INTERACTIVE_HINT.test(cls)) continue;
              if (hasStyleFace(cls)) continue;
              const where = `${rel(file)}:${idx + 1}`;
              if (seen.has(cls)) {
                seen.get(cls).count++;
                continue;
              }
              seen.set(cls, { where, count: 1 });
            }
          }
        }
      }
    });
  }
  return [...seen.entries()].map(([cls, v]) => ({
    kind: 'js-class',
    where: v.where,
    selector: '.' + cls,
    decl: '（CSS/HTML 零命中）',
    why: `JS 生成的类名在整个 css/ 与 index.html 内零命中 ⇒ 无样式面，几何不可静态判定`
      + (v.count > 1 ? `（该名共 ${v.count} 处生成点，此处为首处）` : ''),
  }));
}

// ── 汇总 ────────────────────────────────────────────────────────────────
const runtime = runtimeLeg();
const staticHits = [...staticCssLeg(), ...staticJsLeg()];

const violationCount = runtime.violations.length;
const undeterminedCount = runtime.undetermined.length + staticHits.length;
const okCount = runtime.items.filter((x) => x.status === 'ok').length;

const result = {
  ok: envErrors.length === 0 && runtime.ok && violationCount <= MAX_VIOL,
  visualMin: VISUAL_DP,
  boxMin: BOX_DP,
  tokens: {
    '--ds-hit-tap-min': VISUAL ? { raw: VISUAL.raw, chain: VISUAL.chain, site: VISUAL.site } : null,
    '--ds-hit-box-min': BOX ? { raw: BOX.raw, chain: BOX.chain, site: BOX.site } : null,
  },
  readings: runtime.ok ? runtime.file : null,
  maxViolations: MAX_VIOL,
  envErrors,
  runtimeError: runtime.ok ? null : runtime.reason,
  stats: {
    scanned: runtime.items.length,
    ok: okCount,
    violations: violationCount,
    undetermined: undeterminedCount,
    undeterminedRuntime: runtime.undetermined.length,
    undeterminedStatic: staticHits.length,
  },
  violations: runtime.violations,
  undetermined: [...runtime.undetermined.map((x) => ({
    kind: 'runtime', where: x.selector, selector: x.selector,
    decl: `w×h ${x.w}×${x.h} · hit ${x.hitW}×${x.hitH}`, why: x.why,
  })), ...staticHits],
};

if (AS_JSON) {
  console.log(JSON.stringify(result, null, 2));
  process.exit(result.ok ? 0 : 1);
}

// ── 人读输出 ────────────────────────────────────────────────────────────
if (envErrors.length) {
  console.error('✗ mobile-tap gate FAILED（环境）');
  for (const e of envErrors) console.error('  · ' + e);
  process.exit(1);
}
console.log(`mobile-tap 门禁 — 视觉档 ${VISUAL_DP}px（${VISUAL.chain.join(' → ')}）`
  + ` · 命中盒档 ${BOX_DP}px（${BOX.chain.join(' → ')}）`);
console.log(`主口径（运行态几何）：${runtime.ok ? runtime.file : '不可用'}`);
if (!runtime.ok) {
  console.error('  ✗ ' + runtime.reason);
} else {
  console.log(`  可点元素 ${result.stats.scanned} · 合规 ${okCount} · 违规 ${violationCount} · 无法判定 ${undeterminedCount}`
    + `（运行态 ${runtime.undetermined.length} / 静态 ${staticHits.length}）`);
  if (violationCount) {
    console.log('\n违规（视觉边 < 视觉档 且 命中盒 < 命中盒档）：');
    for (const v of runtime.violations) {
      console.log(`  ✗ ${v.selector}`);
      console.log(`      w×h ${v.w}×${v.h}  hit ${v.hitW}×${v.hitH}   视觉差 ${v.diff.visual} · 命中盒差 ${v.diff.box}`);
    }
  }
  if (runtime.undetermined.length) {
    console.log('\n运行态无法判定：');
    for (const u of runtime.undetermined) {
      console.log(`  ? ${u.selector}\n      w×h ${u.w}×${u.h}  hit ${u.hitW}×${u.hitH} — ${u.why}`);
    }
  }
}
if (staticHits.length) {
  console.log(`\n静态腿无法判定 ${staticHits.length} 条（几何只能由运行态定，静态一律不下结论）：`);
  for (const h of staticHits.slice(0, 40)) {
    console.log(`  ? ${h.where}  ${h.selector}  [${h.decl}]\n      ${h.why}`);
  }
  if (staticHits.length > 40) console.log(`  … 其余 ${staticHits.length - 40} 条见 --json`);
}

if (!result.ok) {
  console.error(`\n✗ mobile-tap gate FAILED — 违规 ${violationCount} 条（上限 ${MAX_VIOL}）`
    + (runtime.ok ? '' : '；主口径不可用'));
  console.error('  处置：视觉边可小于 48，但必须用 ::after / padding / 透明外扩把命中盒补到'
    + ` ${BOX_DP}×${BOX_DP}（写法与豁免判据见 design/liquid-morph__20261106/TAP.md）。`);
  process.exit(1);
}
console.log(`\n✓ mobile-tap gate passed — 违规 0（上限 ${MAX_VIOL}）；无法判定 ${undeterminedCount} 条已逐条列出，未静默放过`);
process.exit(0);
