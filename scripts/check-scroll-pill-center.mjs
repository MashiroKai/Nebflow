#!/usr/bin/env node
// check-scroll-pill-center.mjs — 会话面胶囊「水平居中」几何门禁
// （作者令 2026-11-06 08:05 逐字：「我看到了 你现在是把之前居中的 新消息通知的
//  胶囊全部靠边了 不要靠边 就保持之前的居中」＋「顺手加一条几何门禁：该胶囊
//  水平居中偏差 >0.5px 即报违规，防回退」）。
//
// 为什么用静态门禁而不是只靠 spec 量测：spec 要起浏览器、只在 CI 前端腿跑；
// 「靠边」这类回退是**一行 CSS**的事（`left:50%; transform:translateX(-50%)`
// 被换成 `right:16px`），静态面能 0 依赖地把它挡住，改一行就红。
//
// 判据（单一源 = css/chat.css 的 `.scroll-new-pill` 段；一族三消费共用）：
//   C1 形态声明块全仓恰一处（`.scroll-new-pill {`），且只在 css/chat.css ——
//      第二处 = 有人又造了一套形态。
//   C2 该块必须声明 `left: 50%`（不得出现 `right:` / `left: <长度>`）。
//   C3 该块必须声明 `transform: translateX(-50%)`。
//   C4 凡是选择器命中 `.scroll-new-pill` 且声明了 transform 的**每一态**
//      （hover / active / focus / reduced-motion / 手机层抬层…）都必须保留
//      `translateX(-50%)` —— 丢一态 = 该态下按钮横跳/靠边。
//   C5 不许出现任何 `scroll-pill` 家族的第二套定位（`right:` 定位、`margin-left:
//      auto`、`justify-self: end` 之类右靠边手法）。
//
// 运行：node scripts/check-scroll-pill-center.mjs [--json]
// 退出码：0 = 绿（居中口径成立）；1 = 红（靠边/多源/丢态）；2 = 环境错误。

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const CSS_DIR = join(ROOT, 'src', 'main', 'resources', 'web', 'css');
const SINGLE_SOURCE = 'chat.css';
const PILL_CLASS = 'scroll-new-pill';
const CENTER_LEFT = 'left: 50%';
const CENTER_TRANSFORM = 'translateX(-50%)';

const JSON_OUT = process.argv.includes('--json');
const violations = [];

function v(code, file, line, msg) {
  violations.push({ code, file: file ? relative(ROOT, file) : '', line: line || 0, msg });
}

/** 逐条 CSS 规则粗解（去注释 → 扁平化嵌套 → 取「选择器 { 声明体 }」）。
 *  只用最小解析：本门禁只关心三条声明与选择器归属，不追求 CSS 全语法。 */
function rules(css) {
  // 注释替换成等长空白（保留行号），门禁报行号必须与源文件一致
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
  const out = [];
  const re = /([^{}]+)\{([^{}]*)\}/g;
  let m;
  while ((m = re.exec(clean))) {
    const sel = m[1].trim().replace(/\s+/g, ' ');
    const body = m[2];
    // 行号 = 选择器首个非空白字符所在行（不是「上一规则结束」的位置）
    const selStart = m.index + m[1].indexOf(m[1].trim());
    const line = clean.slice(0, selStart).split('\n').length;
    out.push({ sel, body, line });
  }
  return out;
}

function decls(body) {
  const out = {};
  for (const part of body.split(';')) {
    const i = part.indexOf(':');
    if (i < 0) continue;
    const k = part.slice(0, i).trim().toLowerCase();
    const val = part.slice(i + 1).trim();
    if (k) out[k] = val;
  }
  return out;
}

const files = readdirSync(CSS_DIR).filter((f) => f.endsWith('.css'));
const parsed = files.map((f) => ({ file: join(CSS_DIR, f), name: f, rules: rules(readFileSync(join(CSS_DIR, f), 'utf8')) }));

// ── C1 形态声明块唯一 ────────────────────────────────────────────────────
const declBlocks = [];
for (const p of parsed) {
  for (const r of p.rules) {
    // 顶格形态声明块 = 选择器里**只有**这一类（允许 .hidden / 伪类等修饰）
    // 「形态声明块」的机械判据 = 顶格命中该类 **且** 声明了 position（只有形态
    // 块定位自己的盒子；reduced-motion 里那类只写 animation/transition 的补丁块
    // 不算第二套形态）。
    if (new RegExp(`^\\.${PILL_CLASS}\\s*$`).test(r.sel) && /position\s*:/.test(r.body)) {
      declBlocks.push({ ...r, file: p.file, name: p.name });
    }
  }
}
if (declBlocks.length !== 1) {
  v('C1', declBlocks[0]?.file, declBlocks[0]?.line,
    `形态声明块 .${PILL_CLASS}{ position: … } 应恰 1 处，实得 ${declBlocks.length} 处` +
    (declBlocks.length > 1 ? `（${declBlocks.map((b) => b.name + ':' + b.line).join(', ')}）` : ''));
} else if (declBlocks[0].name !== SINGLE_SOURCE) {
  v('C1', declBlocks[0].file, declBlocks[0].line, `形态声明块应在 css/${SINGLE_SOURCE}`);
}

// ── C2/C3 居中三件套在该块里 ─────────────────────────────────────────────
const main = declBlocks[0];
if (main) {
  const d = decls(main.body);
  const left = (d.left || '').replace(/\s+/g, ' ').trim();
  if (left !== '50%') v('C2', main.file, main.line, `缺居中锚 left: 50%（实得 "${d.left ?? '∅'}"）`);
  if ('right' in d) v('C2', main.file, main.line, `出现 right: ${d.right} —— 靠边定位（本族一律水平居中）`);
  const tr = (d.transform || '').replace(/\s+/g, '');
  if (!tr.includes(CENTER_TRANSFORM.replace(/\s+/g, ''))) {
    v('C3', main.file, main.line, `缺 transform: ${CENTER_TRANSFORM}（实得 "${d.transform ?? '∅'}"）`);
  }
}

// ── C4 每一态都保留居中位移 ──────────────────────────────────────────────
for (const p of parsed) {
  for (const r of p.rules) {
    if (!r.sel.includes(`.${PILL_CLASS}`)) continue;
    const d = decls(r.body);
    if (!('transform' in d)) continue;
    if (!d.transform.replace(/\s+/g, '').includes(CENTER_TRANSFORM.replace(/\s+/g, ''))) {
      v('C4', p.file, r.line, `「${r.sel}」的 transform 丢了 ${CENTER_TRANSFORM}（实得 "${d.transform}"）—— 该态按钮横跳/靠边`);
    }
  }
}

// ── C5 家族不得出现右靠边手法 ────────────────────────────────────────────
const RIGHTWARD = [
  [/\bright\s*:\s*(?!auto)/, 'right:'],
  [/justify-self\s*:\s*(end|right)/, 'justify-self'],
  [/margin-left\s*:\s*auto/, 'margin-left: auto'],
  [/text-align\s*:\s*(right|end)/, 'text-align'],
];
for (const p of parsed) {
  for (const r of p.rules) {
    if (!r.sel.includes(`.${PILL_CLASS}`)) continue;
    const body = r.body.replace(/\s+/g, ' ');
    for (const [re, label] of RIGHTWARD) {
      if (re.test(body)) v('C5', p.file, r.line, `「${r.sel}」出现右靠边手法 ${label}`);
    }
  }
}

// ── C6 家族不得有第二套形态类（单实现批的形态面判据）────────────────────
// 判据：任何 class 选择器里出现 `scroll-*-pill` 而不在 `.scroll-new-pill` 名下的
// ⇒ 红。这条挡的正是 2026-11-05 那类「长得像但另起一套」的复活。
for (const p of parsed) {
  for (const r of p.rules) {
    for (const m of r.sel.matchAll(/\.(scroll-[a-z-]*pill)\b/g)) {
      if (m[1] !== PILL_CLASS) v('C6', p.file, r.line, `「${r.sel}」是第二套胶囊形态类 .${m[1]} —— 本族只有 .${PILL_CLASS}`);
    }
  }
}

// ── 前提：唯一实现件在册（缺席 = 单实现前提不成立，按违规计而非环境错误）──
if (!existsSync(join(ROOT, 'src', 'main', 'resources', 'web', 'js', 'scrollPill.js'))) {
  v('C0', join(ROOT, 'src', 'main', 'resources', 'web', 'js', 'scrollPill.js'), 0,
    '唯一实现件 js/scrollPill.js 缺席（本族三消费的同一工厂前提不成立）');
}

const report = {
  ok: violations.length === 0,
  pill: `.${PILL_CLASS}`,
  source: `${SINGLE_SOURCE}:${main ? main.line : 0}`,
  anchors: `${CENTER_LEFT}; transform: ${CENTER_TRANSFORM}`,
  violations,
};

if (JSON_OUT) {
  console.log(JSON.stringify(report, null, 2));
} else if (report.ok) {
  console.log(`✓ scroll-pill centering gate passed — 形态单源 css/${SINGLE_SOURCE}:${main.line}，` +
    `居中锚 ${CENTER_LEFT} + ${CENTER_TRANSFORM} 在册，全族 ${files.length} 个 css 内 0 处靠边手法/丢态`);
} else {
  console.error(`✗ scroll-pill centering gate FAILED — ${violations.length} 项：`);
  for (const x of violations) console.error(`  ${x.code}  ${x.file}:${x.line}  ${x.msg}`);
  console.error('  规范 = 作者令 2026-11-06 08:05「不要靠边 就保持之前的居中」；' +
    '判据正文见本脚本头注，改判据要连同批文档一起改。');
}

process.exit(report.ok ? 0 : 1);
