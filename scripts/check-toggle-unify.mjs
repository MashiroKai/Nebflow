#!/usr/bin/env node
// check-toggle-unify.mjs — 「全站只有一个开关」静态哨兵
// （toggle-unify 批 2026-11-05；作者裁定 2026-11-05 19:55「A 胶囊滑块」）
//
// Why a gate：本批之前全站有**四套**开关实现，各自画轨道、圆钮、状态与过渡：
//   · `.nb-toggle`（共享组件，36×20 + 两条 inset 内高光）
//   · `.tfl-switch`（todo 面：26×15 轨道 + 11px 圆钮 + 常驻文字，2 个用点）
//   · `.daemon-autostart`（守护进程：label + 隐藏 input[checkbox] + track + 带投影的 thumb）
//   · `.pp-toggle`（感知面板：38×22 + 带投影的 16px 圆钮，且**没有** role/aria-checked）
// 四套的差别只在「谁那天怎么画的」，而人工审查抓不住「有人又画了一套」——
// 因为新画的那套看起来和旧的一样合理。所以把「唯一实现」拆成三条机器判据：
//
// ── 三条判据 ────────────────────────────────────────────────────────────
//  T1 行为唯一：`role="switch"` 只许出现在 `js/toggle.js`（模板串形态与
//     `setAttribute('role','switch')` 形态都算）。其它文件出现 = 有人绕开组件自己
//     造了一个开关语义（键盘/读屏行为随之分裂：`.pp-toggle` 就是这样丢掉了
//     aria-checked —— 读屏用户读不出「开还是关」）。
//  T2 造型唯一：带开关名字（名字段命中 toggle/switch）的选择器，不许在
//     `css/sidebar.css` 之外出现「开关头像」——圆钮/滑片的三条判据：
//        S1 主体是 `::before|::after` 且声明 `border-radius: 50%`（圆钮形态）；
//        S2 声明 `transform: translateX(<非 50% 的值>)`（滑行位移 = 状态通道）。
//     另加 S3：任何带 `:checked` 的规则出现 `translateX(` 即红 —— 那是
//     「隐藏 input + track + thumb」那一族的签名（`.daemon-autostart` 正是如此，
//     它的类名里没有 toggle/switch 字样，只靠名字判会被绕过）。
//  T4 JS 注入面唯一：`js/*.js` 里的 CSS 串 / 标签串同样不许长出一套开关 ——
//     三条签名（S3 的 JS 版）：
//        T4a `:checked … { … translateX(` —— 隐藏 input + track 滑行（.daemon-autostart 原件）；
//        T4b `<input type="checkbox">` 与 `*-track|*-thumb|*-toggle|*-switch` 类名同文件出现
//            —— 「用 checkbox 冒充开关」的标记形态。
//     （`css/*.css` 与 JS 注入串是**两个扫描面**：只扫前者等于给后者留后门，
//       本批的 `.daemon-autostart` 就整族活在 daemons.js 的模板串里。）
//  T3 数值唯一：几何/动效必须来自 `stand-tokens.css` 的 `--nf-toggle-*` 段
//     （40×22 / 16 圆钮 / 999 圆角 / 18 行程 / 180ms 曲线），且 `css/sidebar.css`
//     组件节的 `width` / `height` / `border-radius` 必须走 `var(--nf-toggle-*)`
//     —— 写死数值 = 第二套几何在长回来。
//
// 规范侧（人读条文）：`seed/plugins/design-language/skills/design-language/
// references/flat-button-spec.md` §1.0 / §4.1。本脚本只做机械可判的部分。
//
// 用法：
//   node scripts/check-toggle-unify.mjs            # 门禁（红/绿）
//   node scripts/check-toggle-unify.mjs --json     # 机器读
//   node scripts/check-toggle-unify.mjs --inventory# 盘点（现有开关相关规则块）
//   --root=<dir>  在另一个检出上跑同一判据（改前/改后对照用）
// 退出码：0 = 绿；1 = 红；2 = 环境错误。

import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT_OVERRIDE = (process.argv.slice(2).find((a) => a.startsWith('--root=')) || '').slice(7);
const ROOT = ROOT_OVERRIDE
  ? fileURLToPath(new URL(`file://${ROOT_OVERRIDE}/`))
  : fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');
const CSS_DIR = join(WEB, 'css');
const JS_DIR = join(WEB, 'js');
const TOKENS = join(CSS_DIR, 'stand-tokens.css');

/** 唯一行为实现 / 唯一造型点（改这两个路径 = 改「单点」的定义本身，别顺手） */
const BEHAVIOR_FILE = join(JS_DIR, 'toggle.js');
const SKIN_FILE = join(CSS_DIR, 'sidebar.css');

/** T2：开关名字段（与 check-flat-buttons 的按钮面段同族口径） */
const SWITCH_SEGMENTS = new Set(['toggle', 'toggles', 'switch', 'switches']);

const rel = (p) => relative(ROOT, p);

function stripCssComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}

/** 极简 CSS 块遍历器（与 check-flat-buttons 同口径的最小副本）。 */
function* walkRules(src) {
  const text = stripCssComments(src);
  const lineOf = (idx) => text.slice(0, idx).split('\n').length;
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
      if (!prelude.startsWith('@')) {
        const body = text.slice(bodyStart, j);
        const decls = [];
        let cur = '';
        let declStart = bodyStart;
        for (let k = 0; k <= body.length; k++) {
          const c = body[k];
          if (c === ';' || k === body.length) {
            const colon = cur.indexOf(':');
            if (colon > 0) {
              decls.push({
                prop: cur.slice(0, colon).trim().toLowerCase(),
                value: cur.slice(colon + 1).trim(),
                line: lineOf(declStart),
              });
            }
            cur = '';
            declStart = bodyStart + k + 1;
          } else {
            if (c === '\n') declStart = bodyStart + k + 1;
            cur += c;
          }
        }
        yield { selector: prelude, decls, line: lineOf(selStart) };
      }
      i = j + 1;
      selStart = i;
      continue;
    }
    if (ch === '}') {
      i++;
      selStart = i;
      continue;
    }
    i++;
  }
}

/** 选择器主体（最右复合选择器）——样式落在谁身上。 */
function subjectCompound(part) {
  let depth = 0;
  let last = 0;
  for (let i = 0; i < part.length; i++) {
    const c = part[i];
    if (c === '(' || c === '[') depth++;
    else if (c === ')' || c === ']') depth = Math.max(0, depth - 1);
    else if (depth === 0 && /[\s>+~]/.test(c)) {
      let j = i;
      while (j < part.length && /[\s>+~]/.test(part[j])) j++;
      last = j;
      i = j - 1;
    }
  }
  return part.slice(last).trim();
}

/** 选择器里所有类名段（按 -/_ 切分）。 */
function nameSegments(selector) {
  const out = [];
  for (const m of selector.matchAll(/[.#]([A-Za-z0-9_-]+)/g)) {
    for (const s of m[1].split(/[-_]/)) if (s) out.push(s.toLowerCase());
  }
  return out;
}

function listJs(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (name.endsWith('.js')) out.push(p);
    else if (!name.includes('.') && statSync(p).isDirectory()) out.push(...listJs(p));
  }
  return out;
}

// ── T1：行为唯一 ────────────────────────────────────────────────────────
const ROLE_PATTERNS = [
  /role\s*=\s*["']switch["']/i,                              // HTML 模板串
  /setAttribute\(\s*['"]role['"]\s*,\s*['"]switch['"]/i,     // DOM 形态
];

function checkBehavior() {
  const hits = [];
  if (!existsSync(JS_DIR)) return hits;
  for (const file of listJs(JS_DIR)) {
    let src = '';
    try { src = readFileSync(file, 'utf8'); } catch { continue; }
    src.split('\n').forEach((line, idx) => {
      for (const re of ROLE_PATTERNS) {
        if (re.test(line)) {
          hits.push({ file: rel(file), line: idx + 1, text: line.trim().slice(0, 120) });
          break;
        }
      }
    });
  }
  return hits.filter((h) => h.file !== rel(BEHAVIOR_FILE));
}

// ── T2：造型唯一 ────────────────────────────────────────────────────────
function checkSkin() {
  const hits = [];
  if (!existsSync(CSS_DIR)) return hits;
  for (const name of readdirSync(CSS_DIR).filter((f) => f.endsWith('.css'))) {
    const file = join(CSS_DIR, name);
    if (file === SKIN_FILE) continue; // 唯一造型点本身
    const src = readFileSync(file, 'utf8');
    for (const rule of walkRules(src)) {
      for (const part of rule.selector.split(',')) {
        const subj = subjectCompound(part);
        if (!subj) continue;
        const segs = nameSegments(subj);
        const switchNamed = segs.some((s) => SWITCH_SEGMENTS.has(s));
        // 同一个类（`.nb-toggle`）在任何文件里的几何覆写都**不算第二套** —— 手机层
        // 的 iOS 尺寸档正是这么写的；判据抓的是「另一个类也画开关头像」。
        const sameComponent = /^\.nb-toggle(?![\w-])/.test(subj);
        const hasTransformX = rule.decls.some(
          (d) => d.prop === 'transform' && /translateX\(\s*(?!-?50%)[^)]+\)/i.test(d.value),
        );
        const knobShape = /::?(before|after)\b/i.test(subj)
          && rule.decls.some((d) => d.prop === 'border-radius' && /50%/.test(d.value));
        if (switchNamed && !sameComponent && (hasTransformX || knobShape)) {
          hits.push({
            file: rel(file),
            line: rule.line,
            why: 'S1/S2 开关头像：开关名字段的选择器在组件文件之外画圆钮/滑行位移',
            selector: part.trim(),
          });
        } else if (/:checked\b/.test(part) && hasTransformX) {
          hits.push({
            file: rel(file),
            line: rule.line,
            why: 'S3 隐藏 input 开关族：:checked + translateX 组合（track/thumb 那一套）',
            selector: part.trim(),
          });
        }
      }
    }
  }
  return hits;
}

// ── T4：JS 注入面唯一 ──────────────────────────────────────────────────
const CHECKED_SLIDE_RE = /:checked[^{}]{0,200}\{[^{}]{0,400}translateX\(/;
const CHECKBOX_RE = /type\s*=\s*["']checkbox["']/;
const SWITCH_PART_IN_STRING_RE = /-[a-zA-Z0-9]*(?:track|thumb|switch|toggle)[a-zA-Z0-9]*["'\s]/;

function checkJsInjected() {
  const hits = [];
  if (!existsSync(JS_DIR)) return hits;
  for (const file of listJs(JS_DIR)) {
    if (file === BEHAVIOR_FILE) continue;
    const src = readFileSync(file, 'utf8');
    if (CHECKED_SLIDE_RE.test(src)) {
      hits.push({ file: rel(file), line: lineOfMatch(src, CHECKED_SLIDE_RE), why: 'T4a JS 注入串里出现 :checked + translateX（隐藏 input 开关族）' });
    }
    if (CHECKBOX_RE.test(src) && SWITCH_PART_IN_STRING_RE.test(src)) {
      hits.push({ file: rel(file), line: lineOfMatch(src, CHECKBOX_RE), why: 'T4b checkbox 冒充开关：type="checkbox" 与 *-track/-thumb/-toggle/-switch 类名同文件' });
    }
  }
  return hits;
}

/** 命中串的起始行号（1-based）。 */
function lineOfMatch(src, re) {
  const m = src.match(re);
  if (!m || m.index == null) return 0;
  return src.slice(0, m.index).split('\n').length;
}

// ── T3：数值唯一 ────────────────────────────────────────────────────────
const REQUIRED_TOKENS = [
  ['--nf-toggle-w', '40px'],
  ['--nf-toggle-h', '22px'],
  ['--nf-toggle-knob', '16px'],
  ['--nf-toggle-radius', '999px'],
  ['--nf-toggle-inset', '2px'],
  ['--nf-toggle-hit', '44px'],
  ['--nf-toggle-dur', '180ms'],
];
const REQUIRED_EASE = 'cubic-bezier(0.2, 0, 0, 1)';

function checkTokens() {
  const hits = [];
  if (!existsSync(TOKENS)) {
    return [{ file: rel(TOKENS), line: 0, why: '令牌段缺失：stand-tokens.css 不存在' }];
  }
  const src = readFileSync(TOKENS, 'utf8');
  for (const [name, want] of REQUIRED_TOKENS) {
    const m = src.match(new RegExp(`${name}\\s*:\\s*([^;]+);`));
    if (!m) hits.push({ file: rel(TOKENS), line: 0, why: `缺令牌 ${name}（应 = ${want}）` });
    else if (m[1].trim() !== want) {
      hits.push({ file: rel(TOKENS), line: 0, why: `令牌 ${name} = ${m[1].trim()}，应 = ${want}（规格漂移）` });
    }
  }
  const ease = src.match(/--nf-toggle-ease\s*:\s*([^;]+);/);
  if (!ease) hits.push({ file: rel(TOKENS), line: 0, why: '缺令牌 --nf-toggle-ease' });
  else if (!ease[1].includes(REQUIRED_EASE)) {
    hits.push({ file: rel(TOKENS), line: 0, why: `--nf-toggle-ease = ${ease[1].trim()}，应含 ${REQUIRED_EASE}` });
  }
  const travel = src.match(/--nf-toggle-travel\s*:\s*([^;]+);/);
  if (!travel) hits.push({ file: rel(TOKENS), line: 0, why: '缺令牌 --nf-toggle-travel（行程必须公式算出）' });

  // 组件节必须消费令牌，不许写死几何
  if (existsSync(SKIN_FILE)) {
    const css = readFileSync(SKIN_FILE, 'utf8');
    for (const rule of walkRules(css)) {
      const first = subjectCompound(rule.selector.split(',')[0] || '');
      if (first !== '.nb-toggle') continue;
      for (const d of rule.decls) {
        if (!['width', 'height', 'border-radius'].includes(d.prop)) continue;
        if (!d.value.includes('var(--nf-toggle-')) {
          hits.push({
            file: rel(SKIN_FILE),
            line: d.line,
            why: `组件节写死了 ${d.prop}: ${d.value}（几何必须走 var(--nf-toggle-*)）`,
          });
        }
      }
    }
  }
  return hits;
}

// ── 主流程 ──────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const jsonOut = argv.includes('--json');
const inventory = argv.includes('--inventory');

const behavior = checkBehavior();
const skin = checkSkin();
const tokens = checkTokens();
const injected = checkJsInjected();
const total = behavior.length + skin.length + tokens.length + injected.length;

if (inventory) {
  console.log(`行为单点：${rel(BEHAVIOR_FILE)}`);
  console.log(`造型单点：${rel(SKIN_FILE)}`);
  console.log(`数值单点：${rel(TOKENS)}`);
  let cssBlocks = 0;
  let jsRefs = 0;
  for (const name of readdirSync(CSS_DIR).filter((f) => f.endsWith('.css'))) {
    const src = readFileSync(join(CSS_DIR, name), 'utf8');
    for (const rule of walkRules(src)) {
      const segs = nameSegments(rule.selector);
      if (segs.some((s) => SWITCH_SEGMENTS.has(s))) cssBlocks++;
    }
  }
  for (const file of listJs(JS_DIR)) {
    const src = readFileSync(file, 'utf8');
    jsRefs += (src.match(/nb-toggle|toggleHTML\(|createToggle\(/g) || []).length;
  }
  const skinWrites = (readFileSync(SKIN_FILE, 'utf8').match(/\.nb-toggle/g) || []).length;
  console.log(`\n名字含 toggle/switch 的 CSS 规则块：${cssBlocks}（其中 .nb-toggle 写法 ${skinWrites} 处）`);
  console.log(`js 侧对共享组件的引用点（nb-toggle / toggleHTML / createToggle）：${jsRefs}`);
  console.log(`\n判据读数：T1 违规 ${behavior.length} · T2 违规 ${skin.length} · T3 违规 ${tokens.length} · T4 违规 ${injected.length}`);
  process.exit(0);
}

if (jsonOut) {
  console.log(JSON.stringify({ behavior, skin, tokens, injected, total }, null, 2));
  process.exit(total === 0 ? 0 : 1);
}

if (total) {
  console.error('✗ toggle-unify gate FAILED');
  console.error(`  违规 ${total} 处（T1 行为 ${behavior.length} · T2 造型 ${skin.length} · T3 令牌 ${tokens.length} · T4 JS 注入 ${injected.length}）`);
  for (const h of behavior) console.error(`  ${h.file}:${h.line}  [T1] 开关语义在组件之外被重新声明\n      ${h.text}`);
  for (const h of skin) console.error(`  ${h.file}:${h.line}  [T2] ${h.why}\n      ${h.selector}`);
  for (const h of tokens) console.error(`  ${h.file}:${h.line}  [T3] ${h.why}`);
  for (const h of injected) console.error(`  ${h.file}:${h.line}  [T4] ${h.why}`);
  console.error('\n处置：开关一律 = js/toggle.js 的导出（toggleHTML / createToggle / bindToggle /'
    + '\nsetToggleState / setToggleBusy）+ .nb-toggle 类；几何与动效改 stand-tokens.css 的'
    + '\n--nf-toggle-* 段；要新增开关形态先改规范（flat-button-spec.md §1.0/§4.1），别直接画。');
  process.exit(1);
}

console.log('✓ toggle-unify gate passed — 行为单点 js/toggle.js、造型单点 css/sidebar.css `.nb-toggle`、'
  + `数值单点 stand-tokens.css --nf-toggle-*（${REQUIRED_TOKENS.length + 2} 枚令牌逐值符合 A 组规格）`);
process.exit(0);
