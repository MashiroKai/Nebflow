#!/usr/bin/env node
// tests/liquid-morph.spec.mjs — 「液态形变 / Liquid Morph」单源模块单测（零浏览器、零端口）。
//
// 判据三组：
//   A. 模块面（js/motion/liquidMorph.js）：导出面恰好 5 个、逐值断言、morphTransition 的
//      白名单闸 / 第二曲线闸 / 降级闸、node 环境 reducedMotion() 恒 false。
//   B. 令牌镜像（css/design-system.css §㉒）：--lm-dur / --lm-dur-fast / --lm-dur-slow /
//      --lm-ease / --lm-props 与模块**逐值同源**（改一边不改另一边即红 = 单源判据）。
//   C. 原型锚（css/mobileSearchFlat.css）：时长/曲线/属性清单/降级四组读数仍在原位；
//      原型变了 = 语言必须重新对锚（本断言的作用就是逼人回来看）。
//
// Run: node tests/liquid-morph.spec.mjs     （exit 0 = 全绿；1 = 有红）

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as M from '../src/main/resources/web/js/motion/liquidMorph.js';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB = join(ROOT, 'src', 'main', 'resources', 'web');

let pass = 0;
const fails = [];
function ok(name, cond, extra) {
  if (cond) { pass += 1; console.log(`PASS  ${name}`); }
  else { fails.push(name); console.log(`FAIL  ${name}${extra ? ` — ${extra}` : ''}`); }
}
function eq(name, actual, expected) {
  ok(name, JSON.stringify(actual) === JSON.stringify(expected), `actual=${JSON.stringify(actual)} expected=${JSON.stringify(expected)}`);
}
function throws(name, fn, needle) {
  try { fn(); ok(name, false, '没有抛错'); }
  catch (e) { ok(name, String(e.message).includes(needle), `message=${e.message}`); }
}

// ── A. 模块面 ─────────────────────────────────────────────────────────
eq('A1 导出面恰好 5 个（其余一律不导出）',
  Object.keys(M).sort(),
  ['DURATION', 'EASING', 'PROPERTY_WHITELIST', 'morphTransition', 'reducedMotion']);

eq('A2 DURATION = {fast:160, base:280, slow:400}（三档各有既有读数锚点）',
  M.DURATION, { fast: 160, base: 280, slow: 400 });
ok('A3 DURATION 冻结（不可被消费面改写）', Object.isFrozen(M.DURATION));
eq('A4 EASING = cubic-bezier(0.4, 0, 0.2, 1)', M.EASING, 'cubic-bezier(0.4, 0, 0.2, 1)');

eq('A5 PROPERTY_WHITELIST 逐项（原型清单顺序 + 末三项 transform/padding-top/padding-bottom）',
  M.PROPERTY_WHITELIST,
  ['width', 'height', 'margin-left', 'margin-right', 'padding-left', 'padding-right',
    'border-radius', 'border-color', 'background-color', 'box-shadow', 'backdrop-filter',
    'opacity', 'max-width', 'transform', 'padding-top', 'padding-bottom']);
ok('A6 白名单冻结', Object.isFrozen(M.PROPERTY_WHITELIST));
ok('A7 白名单不含不可插值/布局级属性（display/visibility/height auto/max-height/all）',
  ['display', 'visibility', 'max-height', 'content', 'all'].every((p) => !M.PROPERTY_WHITELIST.includes(p)));

// morphTransition：默认 = 全量白名单 + base 档
const full = M.morphTransition();
// 只按「属性名 + 时长」边界切 —— cubic-bezier(0.4, 0, 0.2, 1) 内部本身含 ", "
const parts = full.split(/,\s(?=[a-z-]+\s+\d)/);
eq('A8 默认串 = 逐条属性各一份（14 条，永不含 all）', parts.length, M.PROPERTY_WHITELIST.length);
ok('A9 默认串每条 = "<prop> 280ms cubic-bezier(0.4, 0, 0.2, 1)"',
  M.PROPERTY_WHITELIST.every((p, i) => parts[i] === `${p} 280ms cubic-bezier(0.4, 0, 0.2, 1)`),
  parts[0]);
ok('A10 默认串零 "all"', !/\ball\b/.test(full));

eq('A11 子集 + fast 档',
  M.morphTransition(['width', 'border-radius'], { duration: 'fast' }),
  'width 160ms cubic-bezier(0.4, 0, 0.2, 1), border-radius 160ms cubic-bezier(0.4, 0, 0.2, 1)');
eq('A12 slow 档 + delay',
  M.morphTransition(['transform'], { duration: 'slow', delay: 20 }),
  'transform 400ms cubic-bezier(0.4, 0, 0.2, 1) 20ms');
eq('A13 降级：reduced:true ⇒ 1ms（与原型 :210 同值）',
  M.morphTransition(['opacity'], { reduced: true }),
  'opacity 1ms cubic-bezier(0.4, 0, 0.2, 1)');
eq('A14 显式毫秒数可覆盖档位', M.morphTransition(['opacity'], { duration: 240 }),
  'opacity 240ms cubic-bezier(0.4, 0, 0.2, 1)');

throws('A15 白名单外属性 ⇒ 抛错（不许给不可插值属性做过渡）',
  () => M.morphTransition(['max-height']), '不在液态形变白名单内');
throws('A16 空数组 ⇒ 抛错', () => M.morphTransition([]), '非空数组');
throws('A17 第二套缓动 ⇒ 抛错（禁止第二套缓动）',
  () => M.morphTransition(['width'], { easing: 'cubic-bezier(0.16, 1, 0.3, 1)' }), '禁止第二套缓动');
throws('A18 不存在的时长档 ⇒ 抛错', () => M.morphTransition(['width'], { duration: 'xl' }), '不存在');
throws('A19 负 delay ⇒ 抛错', () => M.morphTransition(['width'], { delay: -1 }), '非负');

eq('A20 node 环境 reducedMotion() 恒 false（模块可被 node 直接 import 的原因）', M.reducedMotion(), false);

// ── B. 令牌镜像（design-system.css §㉒）────────────────────────────────
const dsCss = readFileSync(join(WEB, 'css', 'design-system.css'), 'utf8');
const protoCss = readFileSync(join(WEB, 'css', 'mobileSearchFlat.css'), 'utf8');

/** 取**首个**同名自定义属性声明的值（基档；reduce 覆盖档另由 B12 断言）。 */
function tokenValues(src, name) {
  const out = [];
  const re = new RegExp(`--${name}\\s*:([^;]*);`, 'g');
  for (const m of src.matchAll(re)) out.push(m[1].replace(/\s+/g, ' ').trim());
  return out;
}
const first = (src, name) => { const v = tokenValues(src, name); return v.length ? v[0] : null; };

ok('B1 design-system.css 有 --lm-* 令牌组', ['--lm-dur', '--lm-dur-fast', '--lm-dur-slow', '--lm-ease', '--lm-props']
  .every((t) => dsCss.includes(t)));

eq('B2 --lm-dur = 280ms（= DURATION.base）', first(dsCss, 'lm-dur'), `${M.DURATION.base}ms`);
eq('B3 --lm-dur-fast = var(--dur-2)（= DURATION.fast 的源）', first(dsCss, 'lm-dur-fast'), 'var(--dur-2)');
eq('B4 --lm-dur-slow = var(--ds-dur-slow)（= DURATION.slow 的源）', first(dsCss, 'lm-dur-slow'), 'var(--ds-dur-slow)');
eq('B5 --lm-ease = var(--ease-std)（唯一曲线的既有出生点）', first(dsCss, 'lm-ease'), 'var(--ease-std)');

// 别名解引用：--dur-2: 160ms / --ds-dur-slow: 400ms / --ease-std: cubic-bezier(0.4, 0, 0.2, 1)
const baseCss = readFileSync(join(WEB, 'css', 'base.css'), 'utf8');
eq('B6 --dur-2 解引用 = DURATION.fast', first(baseCss, 'dur-2'), `${M.DURATION.fast}ms`);
eq('B7 --ds-dur-slow 解引用 = DURATION.slow', first(dsCss, 'ds-dur-slow'), `${M.DURATION.slow}ms`);
eq('B8 --ease-std 解引用 = EASING', first(baseCss, 'ease-std'), M.EASING);

// --lm-props 与 PROPERTY_WHITELIST 逐项同序（含跨行续写）
const propsRaw = first(dsCss, 'lm-props');
const propsCss = propsRaw ? propsRaw.split(',').map((s) => s.trim()).filter(Boolean) : [];
eq('B9 --lm-props ≡ PROPERTY_WHITELIST（同项同序）', propsCss, [...M.PROPERTY_WHITELIST]);

// ㉒ 组零色值字面量（strict-file 门禁同款判据，这里对新增段做定点复核）
const groupStart = dsCss.indexOf('㉒ 液态形变');
const groupEnd = groupStart >= 0 ? dsCss.indexOf('@media (hover: hover)', groupStart) : -1;
ok('B10 定位到 ㉒ 令牌组文本段', groupStart > 0 && groupEnd > groupStart);
const group = groupStart > 0 ? dsCss.slice(groupStart, groupEnd) : '';
const colorHits = group.match(/#[0-9a-fA-F]{3,8}\b|\brgba?\([^)]*\)|\bhsla?\([^)]*\)/g);
eq('B11 ㉒ 组零色值字面量', colorHits, null);
ok('B12 ㉒ 组 reduced-motion 降级为 1ms（三档全压近零）',
  /@media \(prefers-reduced-motion: reduce\)\s*\{[\s\S]*?--lm-dur-fast:\s*1ms[\s\S]*?--lm-dur:\s*1ms[\s\S]*?--lm-dur-slow:\s*1ms/.test(group));

// ── C. 原型锚（原型动了 = 回来重新对锚）──────────────────────────────
eq('C1 原型时长读数 --mhsc-dur = DURATION.base', first(protoCss, 'mhsc-dur'), `${M.DURATION.base}ms`);
eq('C2 原型曲线读数 --mhsc-ease = EASING', first(protoCss, 'mhsc-ease'), M.EASING);
// 原型之外的**申报追加项**（每一项都要在 DESIGN.md §3 有申报行；改这个集合 = 改语言）。
const APPENDED = ['transform', 'padding-top', 'padding-bottom'];
ok(`C3 原型 transition 清单覆盖其余 ${M.PROPERTY_WHITELIST.length - APPENDED.length} 项（追加项 ${APPENDED.join('/')} 原型无）`,
  M.PROPERTY_WHITELIST.filter((p) => !APPENDED.includes(p)).every((p) => new RegExp(`${p.replace(/-/g, '\\-')} var\\(--mhsc-dur\\)`).test(protoCss)));
ok('C4 原型 reduced-motion 降级仍为 1ms', /@media \(prefers-reduced-motion: reduce\)[\s\S]*?--mhsc-dur:\s*1ms/.test(protoCss));

// ── 汇总 ─────────────────────────────────────────────────────────────
console.log(`\nliquid-morph: PASS ${pass} · FAIL ${fails.length}`);
if (fails.length) { console.log(`失败项：${fails.join(' | ')}`); process.exit(1); }
process.exit(0);
