// scripts/lib/message-item-css-audit.mjs — 消息条目统一门的 CSS 判定核心
// （2026-11-18 复核重写：归一化 CSS AST 全声明审计；供
//  scripts/check-message-item-unify.mjs 与其盲区证明 tests 共用）。
//
// 旧版盲区（每条都在 scripts/tests/message-item-css-audit.test.mjs 有「造出来能红」的反证）：
//   B1 第二基座块不写枚举属性（`.duration-badge { padding: … }` 无 border-radius）
//      ⇒ 旧判据只数带 border-radius 的块，绿。
//   B2 变体重写未枚举属性（`.pop-item-footer` 上下文变体写 padding）
//      ⇒ 旧判据只查枚举属性，绿。
//   B3 近似但不相同的选择器（`#input-bar` vs `#input`、`.pop-group` vs `.pop-group-item`）
//      ⇒ 旧判据按子串名匹配，会把 `#input-bar` 当 `#input`（假红）或把
//      `.pop-group-item` 当 `.pop-group`（假红）。
//   B4 `:is()` 内空白/逗号破坏复合选择器切分（`body.mobile-layer :is(.duration-badge .msg-copy,
//      .duration-badge-time)` 被切出 `.duration-badge` 当主语 ⇒ 假红）。
//   B5 声明归一化缺口（`.x { PADDING:4px }` / 值内多余空白）⇒ 旧判据按属性名子串与原始
//      body 正则，从不做 prop 小写/值空白归一的声明级比较。
//
// 现行判据（同一个规范化 AST：注释剥除、@ 规则栈、声明按括号深度 0 的 `;` 切分、
// prop 小写、值空白折叠、选择器按括号/方括号/引号深度感知切分）：
//   (a) 基座第二份即红 —— 选择器项**恰好等于**成员 token（`.pop-group`、`#input` 等，
//       无祖先/复合/伪类修饰）的规则块，同一 (成员, 属性, 层级) 只许声明一次。
//       层级 = 档位语境（标准 / 暗色 / 窄屏 / 减动效 / 手机层）；跨层级改写是分层机制的
//       合法形态。唯一豁免 = glass.css「同值复述块」（G9c 级联复述教义）：同一文件族内
//       归一化值逐值相等的复述放行，值不同 = 第二套材质，红。
//   (a2) identity 属性必须由命名两侧面的单一定义块给出（multi-member 族）：共享几何
//       （见 identityProps）在两个成员上都必须来自同一个规则块，且该块命名了全部成员
//       —— 否则 = 两侧面各写一套。
//   (b) 变体白名单 —— 主语解析出成员的上下文变体（祖先/复合类/伪类/`:is()` 内嵌），
//       声明的属性必须在族白名单里；`inherit/unset/initial/revert/revert-layer`
//       这类级联透传值不算「第二份声明」，恒放行。
//   (c) 降级块不参战 —— `@supports not` / `html.no-backdrop` 上下文是「摘除」，归
//       check-glass-unify 的 G1/G9 口径，此处跳过（同值同判，不双标）。
//
// 主语解析纪律（B3/B4 的修法）：
//   · 顶层切分（`splitSelectorList` / `splitChain`）= 括号 / 方括号 / 引号 / 转义感知，
//     `:is(a b, c)` 内的空白与逗号不参与外层切分；
//   · 成员命中 = **整 token 等值**（`#input` 不得命中 `#input-bar`，`.pop-group` 不得
//     命中 `.pop-group-item`）；
//   · 复合选择器先剥去函数伪类内容再取 token ⇒ `:is(.duration-badge .msg-copy, …)`
//     的外层复合不会把内嵌的祖先 `.duration-badge` 当主语；`:is()/:where()` 备选逐项
//     展开后只认**它自己的主语**（`.duration-badge .msg-copy` ⇒ 主语 `.msg-copy`）。

/** 级联透传值：不是「第二份声明」，只是把值交给上一层。 */
export const PASSTHROUGH_VALUES = new Set(['inherit', 'initial', 'unset', 'revert', 'revert-layer']);

/** 族登记表：成员 token → 族。
 *  variantProps = 该族**合法上下文变体**允许的属性全集（由现存变体逐条登记；
 *  新增变体属性 = 改这张表，白名单外即红）。
 *  identityProps = multi-member 族的**共享几何**：两个成员上都必须由同一个命名
 *  两侧面的单定义块给出（空数组 = 该族无共享几何块，单份由 (a) 保证）。 */
export const FAMILY = {
  bubble: {
    label: '气泡外壳',
    members: ['.bubble', '.fm-msg-bubble'],
    // `.bubble` 基座在 chat.css，`.fm-msg-bubble` 是 fm 窗的调制面（共用
    // chatItem.messageBubbleNode 节点 + --ds-bubble-* 令牌单源）⇒ 无共享几何块。
    identityProps: [],
    variantProps: ['color', 'background', 'border', 'border-color', 'border-width',
      'border-bottom-right-radius', 'border-bottom-left-radius', 'border-radius', 'box-shadow',
      'font-size', 'padding', 'max-width', 'min-width', 'margin', 'margin-top', 'transform-origin',
      'display', 'animation', 'opacity', 'overflow-wrap', 'white-space', 'flex-direction', 'gap',
      'width', 'backdrop-filter', '-webkit-backdrop-filter'],
  },
  attach: {
    label: '附件区',
    members: ['.pop-group', '.fm-att-list'],
    identityProps: ['display', 'flex-direction', 'align-items', 'min-width'],
    variantProps: ['align-items', 'max-width', 'gap', 'margin-top'],
  },
  footer: {
    label: '通用 footer 基座',
    members: ['.duration-badge'],
    identityProps: ['display', 'align-items', 'gap', 'margin-top', 'padding', 'background',
      'border-radius', 'box-shadow', 'user-select', 'width'],
    variantProps: ['font-size', 'gap', 'background', 'box-shadow', 'opacity', 'cursor',
      'color', 'position', 'display', 'align-items', 'justify-content', 'content', 'left', 'top',
      'width', 'height', 'transform', 'border-radius', 'transition'],
  },
  stackCard: {
    label: '叠卡展开态每卡 footer',
    members: ['.pop-item-footer'],
    identityProps: ['margin-top'],
    variantProps: [],
  },
  stackGroup: {
    label: '叠卡合起态整组 footer',
    members: ['.pop-stack-group-footer'],
    identityProps: [],
    variantProps: [],
  },
  shell: {
    label: 'composer 外壳',
    members: ['#input-bar', '.fm-input-bar'],
    // input.css 的 `#input-bar, .fm-input-bar` 单一定义块（外壳几何一条）。
    identityProps: ['position', 'pointer-events', 'border-radius', 'padding', 'display',
      'align-items', 'gap', 'flex-shrink'],
    // `bottom` 为本族**第 18 项**具名登记（2026-11-18 整合裁定 (a)：闸自己给的动作就是登记，
    // 登记 = 具名决策点，不是阈值/例外放宽）。
    //   来源：#9 移动浮层/玻璃批（源支 fix/mobile-overlay-glass-20261118，64886c509…e5789f52b）
    //         把手机会话页输入条改成**悬浮件**——底缘锚 dock 净空单源。
    //   落点（唯一）：`body.mobile-layer .mobile-page[data-page='conversation'] .fm-input-bar`
    //         的 `bottom: var(--mobile-dock-clearance)`（css/mobileLayer.css）。
    //   边界：仅 `bottom` 一属性、仅此页变体下的悬浮定位；不涉阈值、不改 M6–M11 判据、
    //         不放宽任何例外表。
    variantProps: ['margin', 'margin-bottom', 'padding', 'border-color', 'background', 'outline',
      'outline-offset', 'transition', 'transition-duration', 'content', 'position', 'top', 'left',
      'right', 'bottom', 'height', 'pointer-events', 'z-index'],
  },
  field: {
    label: 'composer 内件',
    members: ['#input', '.fm-input'],
    // input.css 的 `#input, .fm-input` 单一定义块（内件几何一条）。
    identityProps: ['padding', 'font-size', 'width', 'line-height', 'resize', 'overflow-y',
      'max-height', 'font-family', 'box-sizing', 'color'],
    variantProps: ['color', 'opacity', 'cursor', 'outline', 'box-shadow', 'flex', 'min-width',
      'background', 'border', 'caret-color'],
  },
};

const MEMBER_TO_FAMILY = new Map(
  Object.values(FAMILY).flatMap((f) => f.members.map((m) => [m, f])),
);

/** 归一化 CSS AST 遍历器 → { selector, decls:[{prop,value}], line, atStack }。
 * 注释剥除（等长空白保行号）、@ 规则栈、声明按括号深度 0 的 `;` 切分、
 * prop 小写、值空白折叠。 */
export function* walkCssAst(text) {
  const src = text.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
  const lineOf = (idx) => src.slice(0, idx).split('\n').length;
  let i = 0;
  let selStart = 0;
  const stack = [];
  while (i < src.length) {
    const ch = src[i];
    if (ch === '{') {
      const prelude = src.slice(selStart, i).trim();
      if (prelude.startsWith('@')) {
        stack.push(prelude.replace(/\s+/g, ' '));
        i++;
        selStart = i;
        continue;
      }
      let j = i + 1;
      let inner = 0;
      while (j < src.length) {
        if (src[j] === '{') inner++;
        else if (src[j] === '}') { if (!inner) break; inner--; }
        j++;
      }
      const decls = splitDeclarations(src.slice(i + 1, j));
      yield {
        selector: prelude.replace(/\s+/g, ' '),
        decls,
        line: lineOf(selStart),
        atStack: [...stack],
      };
      i = j + 1;
      selStart = i;
      continue;
    }
    if (ch === '}') {
      if (stack.length) stack.pop();
      i++;
      selStart = i;
      continue;
    }
    i++;
  }
}

/** 声明切分：只切括号深度 0 的 `;`（值内 `url()`/`data:` 的分号不受伤），
 *  prop 小写、值空白折叠（B5）。 */
export function splitDeclarations(body) {
  const decls = [];
  let cur = '';
  let depth = 0;
  const flush = () => {
    const col = cur.indexOf(':');
    if (col > 0) {
      decls.push({
        prop: cur.slice(0, col).trim().toLowerCase(),
        value: cur.slice(col + 1).trim().replace(/\s+/g, ' '),
      });
    }
    cur = '';
  };
  let quote = null;
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (quote) {
      cur += c;
      if (c === quote && body[i - 1] !== '\\') quote = null;
      continue;
    }
    if (c === '"' || c === "'") { quote = c; cur += c; continue; }
    if (c === '(' || c === '[') depth++;
    else if (c === ')' || c === ']') depth = Math.max(0, depth - 1);
    if (c === ';' && depth === 0) { flush(); depth = 0; continue; }
    cur += c;
  }
  flush();
  return decls;
}

/** 顶层切分：括号 / 方括号深度与引号（含转义）感知 ⇒ `:is(a b, c)` 内的空白与
 * 逗号不参与外层切分（B4 的修法）。 */
export function splitTopLevel(s, isSep) {
  const out = [];
  let cur = '';
  let depth = 0;
  let quote = null;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (quote) {
      cur += c;
      if (c === quote && s[i - 1] !== '\\') quote = null;
      continue;
    }
    if (c === '"' || c === "'") { quote = c; cur += c; continue; }
    if (c === '\\') { cur += c; if (i + 1 < s.length) { cur += s[++i]; } continue; }
    if (c === '(' || c === '[') depth++;
    else if (c === ')' || c === ']') depth = Math.max(0, depth - 1);
    if (depth === 0 && isSep(c)) { out.push(cur); cur = ''; continue; }
    cur += c;
  }
  out.push(cur);
  return out.map((x) => x.trim()).filter(Boolean);
}

export const splitSelectorList = (s) => splitTopLevel(s, (c) => c === ',');
export const splitChain = (s) => splitTopLevel(s, (c) => /\s/.test(c) || c === '>' || c === '+' || c === '~')
  .map((x) => x.replace(/^[>+~]\s*/, ''));

/** 剥去函数伪类/伪元素参数内容（括号内全丢），用于取「本复合自己的」token。 */
export function stripFnContents(s) {
  let out = '';
  let depth = 0;
  for (const c of s) {
    if (c === '(') { depth++; continue; }
    if (c === ')') { depth = Math.max(0, depth - 1); continue; }
    if (depth === 0) out += c;
  }
  return out;
}

/** 复合选择器的类/id token（整 token 取词，不做子串匹配）。 */
export function compoundTokens(compound) {
  return [...stripFnContents(compound).matchAll(/[.#][A-Za-z_][\w-]*/g)].map((m) => m[0]);
}

/** 取平衡括号内容（不含最外层括号）；未闭合返回 null。 */
function balancedInner(s, openIdx) {
  let depth = 0;
  for (let i = openIdx; i < s.length; i++) {
    const c = s[i];
    if (c === '(') depth++;
    else if (c === ')') {
      depth--;
      if (depth === 0) return s.slice(openIdx + 1, i);
    }
  }
  return null;
}

/** `:is()/:where()/:-webkit-any()/:matches()` 的备选串（顶层逐组）。 */
function* isWhereGroups(compound) {
  for (const m of compound.matchAll(/:(?:is|where|-webkit-any|matches)\(/g)) {
    const open = compound.indexOf('(', m.index);
    const inner = balancedInner(compound, open);
    if (inner !== null) yield inner;
  }
}

/** 递归收集「以该复合为主语」的成员 token：本复合自己的 token + `:is()/:where()`
 *  备选展开后**各备选自己的主语**（`.a .b` ⇒ 主语 `.b`，`.a` 不算命中）。 */
function collectSubjectMembers(compound, out, depth = 0) {
  if (!compound || depth > 8) return;
  for (const tok of compoundTokens(compound)) if (MEMBER_TO_FAMILY.has(tok)) out.add(tok);
  for (const inner of isWhereGroups(compound)) {
    for (const alt of splitSelectorList(inner)) {
      const chain = splitChain(alt);
      collectSubjectMembers(chain[chain.length - 1] || '', out, depth + 1);
    }
  }
}

/** 选择器项的主语命中的成员 token 集合。 */
export function subjectMembers(item) {
  const chain = splitChain(item);
  const out = new Set();
  collectSubjectMembers(chain[chain.length - 1] || '', out);
  return out;
}

/** 档位语境（跨层级的主题/断点改写是分层机制的合法形态，不算同层级第二份）。 */
export function tierOf(atStack, selector) {
  const at = atStack.join('|');
  if (/prefers-color-scheme:\s*dark/i.test(at)) return 'dark';
  if (/max-width/i.test(at)) return 'narrow';
  if (/prefers-reduced-motion/i.test(at)) return 'rmotion';
  if (/mobile-layer/.test(selector)) return 'mlayer';
  return 'std';
}

const isDegrade = (atStack, selector) => atStack.some((a) => /^@supports\s+not/i.test(a))
  || /html\.no-backdrop/.test(selector);

/**
 * 审计一组 CSS 源文本。
 * @param {Array<{file:string,text:string}>} entries 文件名须为裸名（用于同文件族判定）。
 * @returns {string[]} 违规说明（空数组 = 通过）。
 */
export function auditMessageItemCss(entries) {
  const errors = [];
  /** base: `${member}|${prop}|${tier}` → [{file,line,value,items}] */
  const baseLedger = new Map();
  for (const { file, text } of entries) {
    for (const rule of walkCssAst(text)) {
      const items = splitSelectorList(rule.selector);
      for (const item of items) {
        if (isDegrade(rule.atStack, item)) continue;                 // (c) 降级块不参战
        const tier = tierOf(rule.atStack, item);
        if (MEMBER_TO_FAMILY.has(item)) {                            // (a) 基座 = 恰好等于成员
          for (const d of rule.decls) {
            const key = `${item}|${d.prop}|${tier}`;
            if (!baseLedger.has(key)) baseLedger.set(key, []);
            baseLedger.get(key).push({ file, line: rule.line, value: d.value, items });
          }
          continue;
        }
        for (const member of subjectMembers(item)) {                 // (b) 上下文变体
          const fam = MEMBER_TO_FAMILY.get(member);
          for (const d of rule.decls) {
            if (fam.variantProps.includes(d.prop)) continue;
            if (PASSTHROUGH_VALUES.has(d.value)) continue;
            errors.push(`变体属性越权：css/${file}:${rule.line} ${item} 声明 `
              + `${d.prop}:${d.value.slice(0, 60)}（族「${fam.label}」白名单外`
              + '——新变体属性须登记进 FAMILY.variantProps）');
          }
        }
      }
    }
  }

  // (a) 基座第二份即红（glass.css 同值复述豁免 = G9c 级联复述教义）。
  for (const [key, decls] of baseLedger) {
    if (decls.length <= 1) continue;
    const [member, prop, tier] = key.split('|');
    const first = decls[0];
    const rest = decls.slice(1);
    const allRepeat = first.file === 'glass.css'
      && rest.every((d) => d.file === 'glass.css' && d.value === first.value);
    if (allRepeat) continue;
    for (const d of rest) {
      errors.push(`基座第二份：css/${d.file}:${d.line} ${member} 的 ${prop}（tier=${tier}）`
        + `已由 css/${first.file}:${first.line} 声明（首值 ${first.value.slice(0, 50)} / `
        + `次值 ${d.value.slice(0, 50)}）——同层级第二份 = 分叉定义，收到唯一基座块或登记变体`);
    }
  }

  // (a2) multi-member 族的共享几何必须由同一条命名两侧面的单定义块给出。
  for (const fam of Object.values(FAMILY)) {
    if (fam.members.length < 2 || fam.identityProps.length === 0) continue;
    for (const prop of fam.identityProps) {
      /** @type {Map<string, {file:string,line:number,items:string[]}>} */
      const bySide = new Map();
      for (const member of fam.members) {
        const decls = baseLedger.get(`${member}|${prop}|std`);
        if (decls && decls.length) bySide.set(member, decls[0]);
      }
      if (!bySide.size) continue;
      const blocks = new Set([...bySide.values()].map((d) => `${d.file}:${d.line}`));
      const missing = fam.members.filter((m) => !bySide.has(m));
      const first = [...bySide.values()][0];
      if (blocks.size > 1) {
        errors.push(`两侧面分叉：族「${fam.label}」的共享几何 ${prop} 出现 ${blocks.size} 个基座块`
          + `（${[...blocks].join(', ')}）——应只由一条命名两侧面的单定义块给出`);
      } else if (missing.length) {
        errors.push(`两侧面分叉：族「${fam.label}」的 ${prop} 由 css/${first.file}:${first.line} `
          + `给出，但该块未命名 ${missing.join(' / ')}——共享几何必须命名两侧面`);
      } else if (fam.members.some((m) => !first.items.includes(m))) {
        errors.push(`两侧面分叉：族「${fam.label}」的 ${prop} 所在块 css/${first.file}:${first.line} `
          + `未在一条规则里同时命名 ${fam.members.join(' 与 ')}`);
      }
    }
  }
  return errors;
}
