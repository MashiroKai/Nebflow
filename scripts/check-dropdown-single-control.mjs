#!/usr/bin/env node
// check-dropdown-single-control.mjs — 「同一字段只有一份下拉控件」源码闸
// （2026-11-18 作者实拍报障「电脑端的下拉选框，怎么有重复」修复批）。
//
// 断言（每条都对应一条真实的「同字段两个控件」路径）：
//   D1 收编器**自己拥有整条不变量**：包裹层 inline-grid 栈 + 触发件与原生 select
//      同格叠放 + 原生件不可见/指针穿透（ownStack 的 7 条 inline 声明）。只押
//      「隐藏」不够：栈一失效，两个控件就上下/并排各占一行（作者实拍即此形态）。
//   D2 css/dropdown.css 的同名声明必须与 inline **逐值一致**（双保险可以，分叉
//      不行：同一件事两个真相），且「控件计数」相关的四条（display / grid-area /
//      opacity / pointer-events）必须在 inline 集合里 —— 不许把不变量又单独押回
//      CSS 一份。断因：JS 与 CSS 是分别缓存/分别更新的两份资产（SW 缓存、重启期间
//      逐条 cherry-pick 的落盘即属此类），任一侧缺席时收编照旧发生（trigger 的脸
//      由 **另一个**文件 css/inputSurface.css 供）⇒ 同字段两个控件。
//   D3 全站只有一个下拉形态的构造点（.nf-select 包裹层 / .nf-select-trigger /
//      .nf-select-menu 只在 js/dropdown.js 里创建）—— 不许出现第二套下拉实现。
//   D4 收编器只启动一次（main.js 单点 startSelectEnhancer）。
//   D5 受影响的字段 id 在 web 源树里各只声明一次（旧控件不许以第二份标记/
//      模板形式留下惰性残留）。
//   D6 上游那条「整份样式资产失效」的运行时断言必须在场（tests/
//      dropdown-single-control.spec.mjs 里同时有整份失效与仅隐藏失效两种状态，
//      且 delegate 弹窗头字段在册）—— 防回归把最接近现网的那条覆盖删掉。
//
// 自证检测力（红/绿各一次，退出码 + 日志）：
//   ① 删掉 ownStack 里的 wrap.style.display = 'inline-grid' ⇒ FAIL/1；还原 ⇒ PASS/0
//   ② 把 css/dropdown.css 的 opacity: 0 改成 0.5（分叉）⇒ FAIL/1；还原 ⇒ PASS/0
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve, join } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const web = join(root, 'src', 'main', 'resources', 'web');
const read = (p) => readFileSync(resolve(root, p), 'utf8');

const dropdown = read('src/main/resources/web/js/dropdown.js');
const dropdownCss = read('src/main/resources/web/css/dropdown.css');
const main = read('src/main/resources/web/js/main.js');
const spec = read('tests/dropdown-single-control.spec.mjs');
const errors = [];
const check = (condition, message) => { if (!condition) errors.push(message); };

/** 解析一个 CSS 块的声明（kebab → camel，值去掉空白）。 */
function ruleOf(cssText, selector) {
  const at = cssText.indexOf(selector);
  if (at < 0) return new Map();
  const open = cssText.indexOf('{', at);
  const close = cssText.indexOf('}', open);
  const body = cssText.slice(open + 1, close);
  const out = new Map();
  for (const decl of body.split(';')) {
    const i = decl.indexOf(':');
    if (i < 0) continue;
    const prop = decl.slice(0, i).trim().replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    const value = decl.slice(i + 1).replace(/\s+/g, ' ').trim();
    if (prop) out.set(prop, value);
  }
  return out;
}

// ── D1：收编器自己拥有整条不变量（inline 栈 + 隐藏）────────────────────────
const ownStackBody = (dropdown.match(/function ownStack\(wrap, trigger, select\)\s*\{([\s\S]*?)\n\}/) || [])[1] || '';
check(!!ownStackBody, 'D1 必须存在 ownStack(wrap, trigger, select)（收编器拥有的不变量落点）');
const owned = new Map(); // "target.prop" → value
for (const m of ownStackBody.matchAll(/(wrap|trigger|select)\.style\.([A-Za-z]+)\s*=\s*'([^']*)'/g)) {
  owned.set(`${m[1]}.${m[2]}`, m[3].replace(/\s+/g, ' ').trim());
}
const OWNED_REQUIRED = [
  ['wrap.display', 'inline-grid', '包裹层必须是 inline-grid 栈（否则触发件与旧控件各占一行——作者实拍形态）'],
  ['wrap.verticalAlign', 'middle', '包裹层必须 inline 对齐档（与样式表同值）'],
  ['wrap.maxWidth', '100%', '包裹层必须限宽（与样式表同值）'],
  ['trigger.gridArea', '1 / 1', '触发件必须落同一格（与旧控件叠放）'],
  ['select.gridArea', '1 / 1', '旧控件必须落同一格（与触发件叠放，绝不另起一行）'],
  ['select.opacity', '0', '旧控件必须不可见'],
  ['select.pointerEvents', 'none', '旧控件必须指针穿透（真人永远打不开原生弹层）'],
];
for (const [key, value, why] of OWNED_REQUIRED) {
  check(owned.get(key) === value, `D1 收编器必须在 inline 上拥有 ${key} = ${value}（${why}）；实际：${owned.get(key) ?? '（缺）'}`);
}

// ── D2：CSS 同名声明逐值一致，且不变量不许只留在 CSS 里 ────────────────────
const wrapRule = ruleOf(dropdownCss, '.nf-select {');
const stackRule = ruleOf(dropdownCss, '.nf-select > * {');
const nativeRule = ruleOf(dropdownCss, '.nf-select .nf-select-native {');
const MIRROR = [
  ['wrap.display', wrapRule, 'css/dropdown.css 的 .nf-select 必须声明 display'],
  ['wrap.verticalAlign', wrapRule, 'css/dropdown.css 的 .nf-select 必须声明 vertical-align'],
  ['wrap.maxWidth', wrapRule, 'css/dropdown.css 的 .nf-select 必须声明 max-width'],
  ['trigger.gridArea', stackRule, 'css/dropdown.css 的 .nf-select > * 必须声明 grid-area'],
  ['select.gridArea', stackRule, 'css/dropdown.css 的 .nf-select > * 必须声明 grid-area'],
  ['select.opacity', nativeRule, 'css/dropdown.css 的 .nf-select .nf-select-native 必须声明 opacity'],
  ['select.pointerEvents', nativeRule, 'css/dropdown.css 的 .nf-select .nf-select-native 必须声明 pointer-events'],
];
for (const [key, rule, why] of MIRROR) {
  const cssValue = rule.get(key.split('.')[1]);
  const inlineValue = owned.get(key);
  check(cssValue !== undefined, `D2 ${why}（双保险两侧同值；缺一侧就是「同一件事只有一个真相」被拆开）`);
  check(cssValue !== undefined && cssValue === inlineValue,
    `D2 ${key} 的两处值必须逐值一致（inline=${inlineValue ?? '（缺）'} · css=${cssValue ?? '（缺）'}）`);
}
// 不变量四条不许只在 CSS：D1 已断言 inline 具备；这里反向断言 CSS 侧没有
// 「同一件事的第二种写法」（例如又给旧控件加一条 display:none 之类的旁路）。
const COUNT_CRITICAL = ['display', 'gridArea', 'opacity', 'pointerEvents'];
const stackDeclared = [...wrapRule.keys()].filter((p) => COUNT_CRITICAL.includes(p));
const nativeDeclared = [...nativeRule.keys()].filter((p) => COUNT_CRITICAL.includes(p));
check(stackDeclared.every((p) => owned.get(`wrap.${p}`) === wrapRule.get(p)),
  `D2 .nf-select 里出现收编器不拥有的控件计数声明：${stackDeclared.filter((p) => owned.get(`wrap.${p}`) !== wrapRule.get(p)).join(', ') || '（无）'}`);
check(nativeDeclared.every((p) => owned.get(`select.${p}`) === nativeRule.get(p)),
  `D2 .nf-select-native 里出现收编器不拥有的控件计数声明：${nativeDeclared.filter((p) => owned.get(`select.${p}`) !== nativeRule.get(p)).join(', ') || '（无）'}`);

// ── D3：只有一个下拉构造点（无第二套下拉实现）──────────────────────────────
const jsFiles = [];
const walk = (dir) => {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) walk(full);
    else if (name.endsWith('.js')) jsFiles.push(full);
  }
};
walk(join(web, 'js'));
const triggerWriters = jsFiles.filter((f) => {
  const src = readFileSync(f, 'utf8');
  return /nf-select-trigger|nf-select-val|nf-select-menu/.test(src);
}).map((f) => f.slice(web.length + 1));
check(triggerWriters.length === 1 && triggerWriters[0] === 'js/dropdown.js',
  `D3 下拉形态（.nf-select-trigger/.nf-select-menu）只允许在 js/dropdown.js 构造，实际：${triggerWriters.join(', ') || '（无）'}`);

// ── D4：收编器单点启动 ────────────────────────────────────────────────────
const starts = (main.match(/startSelectEnhancer\(\)/g) || []).length;
check(starts === 1, `D4 main.js 必须恰好调用一次 startSelectEnhancer()（实际 ${starts} 次）`);

// ── D5：受影响字段的 id 在源树里各只声明一次 ───────────────────────────────
const AFFECTED = ['model-role-select-', 'small-llm-select', 'imagegen-settings-select', 'cfg-language', 'cfg-thinking-effort', 'bgagent-model-switch'];
const templates = [
  read('src/main/resources/web/js/modelPanel.js'),
  read('src/main/resources/web/js/bgAgentPopup.js'),
  read('src/main/resources/web/js/sidebar.js'),
];
for (const key of AFFECTED) {
  const literal = templates.join('\n').match(new RegExp(`id="${key.replace(/-/g, '\\-')}`, 'g')) || [];
  const dynamic = templates.join('\n').match(new RegExp(`id = ['"\`]${key.replace(/-/g, '\\-')}|'${key.replace(/-/g, '\\-')}' \\+`, 'g')) || [];
  const sites = literal.length + dynamic.length;
  check(sites <= 1, `D5 受影响字段 ${key} 在模板里出现 ${sites} 次（必须唯一：第二份就是旧控件残留）`);
}

// ── D6：现网那条「整份样式资产失效」的运行时断言必须在场 ──────────────────────
check(/stack-ineffective/.test(spec),
  'D6 tests/dropdown-single-control.spec.mjs 必须保留「整份 dropdown.css 失效」状态（作者实拍形态：栈与隐藏一起失效）');
check(/hiding-ineffective/.test(spec),
  'D6 tests/dropdown-single-control.spec.mjs 必须保留「仅隐藏失效」状态（窄口径回归）');
check(/bgagent-model-switch/.test(spec),
  'D6 delegate 弹窗头字段（#bgagent-model-switch = 作者实拍第一处）必须在运行时计数断言的在册字段里');
check(/as-shipped/.test(spec), 'D6 必须保留 as-shipped 状态（原样即只一个控件）');

if (errors.length) {
  for (const error of errors) console.error('FAIL dropdown-single-control:', error);
  process.exitCode = 1;
} else {
  console.log('PASS dropdown-single-control: 不变量由收编器 inline 拥有（栈+隐藏 7 条）、CSS 同值无分叉、下拉构造单点、收编启动单点、受影响字段标记唯一、现网态运行时断言在册');
}
