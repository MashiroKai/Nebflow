#!/usr/bin/env node
// check-dropdown-single-control.mjs — 「同一字段只有一份下拉控件」源码闸
// （2026-11-18 作者报障「电脑端的下拉选框，怎么有重复」修复批）。
//
// 断言（每条都对应一个真实的「两个控件」路径）：
//   D1 收编器自己拥有旧控件的不可见（inline opacity/pointer-events）—— 不许
//      再把「被替换的原生 select 不可见」单独押在 css/dropdown.css 上：CSS 与
//      JS 是分别缓存/分别更新的两份资产，任一侧陈旧或半更新，旧控件就会重新
//      可见 ⇒ 同字段两个控件（统一 trigger + 原生 select）。
//   D2 css/dropdown.css 的同名声明必须与其**逐值一致**（允许双保险，禁止分叉：
//      两处值不同 = 同一件事两个真相）。
//   D3 全站只有一个下拉形态的构造点（.nf-select 包裹层 / .nf-select-trigger
//      只在 js/dropdown.js 里创建）—— 不许出现第二套下拉实现。
//   D4 收编器只启动一次（main.js 单点 startSelectEnhancer）。
//   D5 受影响的字段 id 在 web 源树里各只声明一次（旧控件不许以第二份标记/
//      模板形式留下惰性残留）。
//
// 自证检测力：把 js/dropdown.js 里 select.style.opacity = '0' 一行删掉 ⇒ 本闸
// 报 FAIL 且退出码 1；还原 ⇒ PASS 且退出码 0。
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve, join } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const web = join(root, 'src', 'main', 'resources', 'web');
const read = (p) => readFileSync(resolve(root, p), 'utf8');

const dropdown = read('src/main/resources/web/js/dropdown.js');
const dropdownCss = read('src/main/resources/web/css/dropdown.css');
const main = read('src/main/resources/web/js/main.js');
const errors = [];
const check = (condition, message) => { if (!condition) errors.push(message); };

// ── D1：收编器自己拥有旧控件的不可见 ──────────────────────────────────────
const ownsOpacity = /select\.style\.opacity\s*=\s*'0'/.test(dropdown);
const ownsPointer = /select\.style\.pointerEvents\s*=\s*'none'/.test(dropdown);
check(ownsOpacity, 'D1 收编器必须在 inline 上关掉被替换原生 select 的 opacity（不可见不许只押给 css/dropdown.css）');
check(ownsPointer, 'D1 收编器必须在 inline 上关掉被替换原生 select 的 pointer-events（指针不许打穿到旧控件）');

// ── D2：CSS 同名声明必须逐值一致（双保险可以，分叉不行）────────────────────
const nativeRule = (dropdownCss.match(/\.nf-select\s+\.nf-select-native\s*\{([^}]*)\}/) || [])[1] || '';
check(/opacity:\s*0\s*;/.test(nativeRule), 'D2 css/dropdown.css 的 .nf-select .nf-select-native 必须声明 opacity: 0（与 inline 同值）');
check(/pointer-events:\s*none\s*;/.test(nativeRule), 'D2 css/dropdown.css 的 .nf-select .nf-select-native 必须声明 pointer-events: none（与 inline 同值）');

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

if (errors.length) {
  for (const error of errors) console.error('FAIL dropdown-single-control:', error);
  process.exitCode = 1;
} else {
  console.log('PASS dropdown-single-control: 旧控件不可见由收编器拥有、CSS 同值、下拉构造单点、收编启动单点、受影响字段标记唯一');
}
