// lib/button-face.mjs — 「按钮面」判定与 CSS 规则块遍历的**唯一实现**
//
// 为什么独立成 lib（ui-design-requirements 批，2026-11-18）：同一个问题——
// 「这条 CSS 规则是不是在给一个按钮上样式」——被两个门禁同时需要：
//   ① `scripts/check-flat-buttons.mjs`：按钮面禁厚度道具（F1–F5）
//   ② `scripts/check-button-source.mjs`：按钮面禁内联绕过 + 族登记同步（B1–B2）
// 两处各写一份判定 = 下一批必然漂移（同一个".nb-toggle 算不算按钮"的问题两处
// 答案不同）。本文件是该判定的唯一出生地，两个门禁都从这里 import。
//
// 判据（机械化，不靠人记账）：选择器里任一复合选择器含
//   ① 类型选择器 button，或属性 [type=button|submit|reset]，或
//   ② 类/id 标记的名字段（按 - _ 切分）命中 BUTTON_SEGMENTS 之一，或
//   ③ 软标记段命中且其后所有段都是修饰词。
// 切分口径：`.cfg-btn-primary` → [cfg, btn, primary]；`.ds-btn--primary` →
// [ds, btn, '', primary]；`#send-btn` → [send, btn]。

/** 硬标记段：类/id 名按 [-_] 切分后**任一段**命中即视为按钮面（btn 无歧义）。
 *
 *  2026-11-05 toggle-unify 批补盲：`toggle` / `switch` 两支进来。为什么它们属于
 *  「按钮面」而不是「容器面」—— 开关是**可点击件**（真实 `<button role="switch">`，
 *  见 js/toggle.js），扁平/统一来源契约对它一视同仁；补之前 `.nb-toggle`
 *  整族落在判据之外。段切分口径不变：`.nb-toggle` → [nb, toggle]；
 *  `.tfl-switch-knob` → [tfl, switch, knob] —— 名字里任一段命中即入扫描范围
 *  （子部件也是这个控件的渲染面，轨道/圆钮的高光同样算厚度）。 */
export const BUTTON_SEGMENTS = new Set([
  'btn', 'btns', 'button', 'buttons',
  'toggle', 'toggles', 'switch', 'switches',
]);

/** 软标记段：命中后还要求「其后所有段都是修饰词」才算按钮面。
 *  为什么不能无条件算：`-pill` 在仓里既做按钮（`.g2-pill-primary` / `.dvp-pill`）
 *  也做**非按钮名词**（`.cal-pill-listbox` 是下拉面板、`.cal-pill-option` 是选项行）
 *  —— 面板/行有影子是合法的扁平禁令之外的东西（它们是容器不是可点击件）。
 *  判据：软段之后只剩修饰词 ⇒ 按钮（`.g2-pill-primary` 的 primary）；
 *       之后出现实词 ⇒ 不是按钮（`listbox` / `option`）。 */
export const SOFT_SEGMENTS = new Set(['pill', 'pills', 'fab', 'fabs']);

/** 软段之后的合法修饰词（状态/档位/形态，全部不改变「这是一个按钮」的事实）。 */
export const MODIFIERS = new Set([
  'primary', 'secondary', 'tertiary', 'danger', 'ghost', 'solid', 'outline', 'flat',
  'sm', 'md', 'lg', 'xl', 'mini', 'tiny', 'big',
  'on', 'off', 'active', 'picked', 'selected', 'disabled', 'open', 'busy',
  'icon', 'toggle', 'group', 'row', 'list', 'block', 'wide', 'full', 'labeled', 'label',
  'chip', 'fab', 'pill',
]);

/** 去掉注释但保留行号（用等量空白/换行替换）。 */
export function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}

/**
 * 极简 CSS 块遍历器：产出 { selector, decls:[{prop,value,line}], line, atStack }。
 * 只关心「规则块」；at-rule 的 prelude 压栈，用来给命中行标注上下文（例如
 * `@media (hover: hover)` 或 `html[data-theme="dark"]`），不改变匹配语义。
 */
export function* walkRules(src) {
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
 * 决定的：`.fwd-btn:hover .fwd-tip` 的样式落在 tooltip 上，不是落在按钮上，拿整串
 * 判按钮会把「提示气泡入场位移」误判成「按钮物理按压」。
 * 伪元素（::before/::after 等）属于按钮自身渲染面，算。
 */
export function subjectCompound(part) {
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

/**
 * 去掉 `:not(...)` 里的内容（平衡括号）。
 * 理由（2026-11-05 并入主干时实测）：`.mh-search-bar:not(.g2-fab)` 的主题是
 * **搜索条**，`:not()` 只是排除条件；把括号里的名字当按钮名，等于让「排除按钮」
 * 变成「是按钮」——判据反了。`:is()/:where()` 里的名字反过来**算**主题名
 * （它们就是主题的一部分），所以只剥 `:not`。
 */
export function stripNotPseudo(comp) {
  let out = '';
  for (let i = 0; i < comp.length; i++) {
    if (comp.startsWith(':not(', i)) {
      let depth = 0;
      let j = i + 4;
      for (; j < comp.length; j++) {
        if (comp[j] === '(') depth++;
        else if (comp[j] === ')') {
          depth--;
          if (depth === 0) break;
        }
      }
      i = j; // 跳过整个 :not(...)
      continue;
    }
    out += comp[i];
  }
  return out;
}

/** 选择器里是否含按钮面（含其修饰）。 */
export function isButtonSelector(selector) {
  for (const part of selector.split(',')) {
    const raw = subjectCompound(part);
    if (!raw) continue;
    const comp = stripNotPseudo(raw);
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

/**
 * 选择器里承载按钮面的类/ID 名（去重、保序）——供「族登记」类判据使用。
 * 只取**主题复合**里的名字（同 isButtonSelector 的口径），并剔掉 `:not(...)`。
 * @param {string} selector
 * @returns {string[]} 形如 ['.panel-btn', '#send-btn']
 */
export function buttonFaceNames(selector) {
  const out = new Set();
  for (const part of selector.split(',')) {
    const raw = subjectCompound(part);
    if (!raw) continue;
    const comp = stripNotPseudo(raw);
    if (!comp) continue;
    for (const n of comp.match(/[.#][A-Za-z0-9_-]+/g) || []) out.add(n);
  }
  return [...out];
}
