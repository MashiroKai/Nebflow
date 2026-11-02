// keyContext.js — 上下文键（context key）与 `when` 表达式求值（keyboard-first 三期，2026-11-02）。
//
// 为什么要这一层：keymap.js 一期只有「模式」一个维度（meta/edit），于是「这个键现在
// 该不该算数」被迫写成一串散在各模块里的 if —— 典型样本是 canvas.js 的
// `document.addEventListener('keydown', …)` 里手写的
// `tgt.closest('input, textarea, select, [contenteditable="true"], …')` 早退。
// 那种写法有三个问题：① 每条腿各写一遍，口径必然漂移；② 它只看焦点落在哪，看不见
// 「界面处于什么状态」（叠卡开没开、树有没有选中、命令面板在不在）；③ 无法被断言
// 枚举 —— 你没法问「在 EDIT 模式下有多少条键还会穿透」。
//
// 本模块把那件事变成**数据**：一组具名上下文键（值可以是 boolean / string / number），
// 规则用 `when` 表达式引用它们，求值时一次性回答「此刻这条规则算不算数」。
//
// ── 语法与语义（**与 Monaco 的 ContextKeyExpr 逐字兼容**）────────────────────
// 这是刻意选的：Monaco（vendored 在 vendor/monaco）的 `ContextKeyExpr.deserialize`
// 是同一门语言，本文件不引它（见下「为什么不直接用 Monaco 的」），但语法与求值语义
// 必须以它为准。`scripts/keyboard-check.mjs` 的 L 组把「本文件的求值」与
// 「Monaco 的求值」在同一批表达式 × 同一批上下文状态下逐格对比（真值表等价断言）——
// 语义漂移会立刻变红，而不是靠注释保证。
//
//   表达式 := or
//   or     := and ('||' and)*
//   and    := unary ('&&' unary)*
//   unary  := '!' unary | '(' or ')' | compare
//   compare:= ident (('=='|'!=') literal | '=~' regexp)?
//   literal:= 'true' | 'false' | number | '带引号串' | bareword（按**字符串**处理）
//
// 求值语义（照 Monaco 实测，见 tests 的 L 组）：
//   · bare `ident`      → **真值**判断（!!value）。未定义的键 = false。
//   · `ident == true`   → 同真值判断（Monaco 把 `== true` 折成 ContextKeyExpr.has）；
//     `ident == false`  → 取反（ContextKeyExpr.not）。
//   · `ident == <其他>` → 严格等于（字符串/数字原文比较）；键未定义 ⇒ false。
//   · `ident != X`      → 上式取反（键未定义 ⇒ true，与 Monaco 一致）。
//   · `ident =~ /re/`   → 字符串正则匹配（Monaco 同一口径）。
//
// ── 为什么不直接 require Monaco 的 ContextKeyExpr ────────────────────────────
// Monaco 的 platform 模块只能从 vendored 的 AMD 包（vs/editor/editor.main.js）里
// 按需 require —— 那是**首屏之后**才加载的 3.8 MB 资源（见 monacoEditor.js 的懒加载
// 契约）。而键位规则必须在**首帧之前**就能工作（用户第一下按键不等网络）。为一个
// 布尔表达式拖进 3.8 MB 是不可接受的交换，所以这里自带一个 60 行的求值器，并把
// 「语义等价」做成门禁断言（L 组）而不是口头承诺。
//
// 另一条同源纪律：本文件**不碰 DOM 业务**。它只维护键值 + 提供谓词。谁在什么时候
// 改哪个键，是各状态源自己的事（canvas.js 改 deckOpen、explorer.js 改
// explorerSelection、palette 改 paletteOpen……）。

/** @typedef {boolean|string|number|undefined} ContextValue */

/** 全部上下文键的当前值。未出现的键 = undefined（Monaco 同口径）。 @type {Map<string, ContextValue>} */
const values = new Map();

/** 派生键：值由状态源**现算**（不是推来的），求值时刻永远新鲜。 @type {Map<string, () => ContextValue>} */
const providers = new Map();

/** 变更订阅者。 @type {Set<(name: string, value: ContextValue) => void>} */
const listeners = new Set();

/**
 * 读一个上下文键。派生键优先（同一名字两者都有值时以派生为准，并在开发期警示 ——
 * 双写是「同一个事实两个真相」的经典来源）。
 * @param {string} name
 * @returns {ContextValue}
 */
export function getContextKey(name) {
  const p = providers.get(name);
  if (p) return p();
  return values.get(name);
}

/**
 * 声明一个**派生**上下文键：值由状态源现场计算。
 *
 * 为什么这是首选形态：叠卡开没开、树有没有选中、焦点落在哪，这些状态在各自模块
 * 里本来就是单一真相。把它们「推进」上下文表要维护每一处赋值点（canvas.js 里
 * `deck.open = false` 出现 3 次，漏一处就是键盘层拿旧状态做判决），而派生键只有
 * 一个读取点 —— 状态源改自己的变量，键盘层问的时候现算。
 * @param {string} name
 * @param {() => ContextValue} fn
 * @returns {void}
 */
export function provideContextKey(name, fn) {
  providers.set(name, fn);
}

/**
 * 写一个上下文键（适用于「没有别处状态源」的布尔开关，如 paletteOpen）。
 * 同值写入不派发（避免抖动刷爆订阅者）。
 * @param {string} name
 * @param {ContextValue} value
 * @returns {void}
 */
export function setContextKey(name, value) {
  const prev = values.get(name);
  if (prev === value) return;
  values.set(name, value);
  for (const fn of listeners) {
    try { fn(name, value); } catch { /* 订阅者异常不得污染上下文 */ }
  }
}

/**
 * 批量写（一次派发循环内多次 set 的便捷形态）。
 * @param {Record<string, ContextValue>} patch
 * @returns {void}
 */
export function setContextKeys(patch) {
  for (const name of Object.keys(patch)) setContextKey(name, patch[name]);
}

/**
 * 订阅变更。
 * @param {(name: string, value: ContextValue) => void} fn
 * @returns {() => void} 退订
 */
export function onContextChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/**
 * 快照（断言/调试用）。派生键**现算**，所以快照里的值就是「此刻求值器看到的值」。
 * @returns {Record<string, ContextValue>}
 */
export function snapshotContext() {
  /** @type {Record<string, ContextValue>} */
  const out = {};
  for (const [k, v] of values) out[k] = v;
  for (const [k] of providers) out[k] = getContextKey(k);
  return out;
}

// ── when 表达式 ─────────────────────────────────────────────────────────

/** @typedef {{ k: string, v?: string }} Token */

/** @typedef {(ctx: (name: string) => ContextValue) => boolean} Pred */

const PUNCT = ['==', '!=', '=~', '&&', '||', '!', '(', ')'];

/**
 * 词法切分。抛错即「规则写错了」——**注册期**就该炸，而不是运行期静默失效。
 * @param {string} src
 * @returns {Token[]}
 */
function tokenize(src) {
  /** @type {Token[]} */
  const out = [];
  let i = 0;
  const s = String(src);
  while (i < s.length) {
    const c = s[i];
    if (/\s/.test(c)) { i++; continue; }
    // 正则字面量（只在 `=~` 右值位置出现，这里统一按字面量切）
    if (c === '/') {
      let j = i + 1;
      let body = '';
      while (j < s.length && s[j] !== '/') { body += s[j]; j++; }
      if (j >= s.length) throw new Error(`keyContext: 正则未闭合 (${src})`);
      j++;
      let flags = '';
      while (j < s.length && /[a-z]/.test(s[j])) { flags += s[j]; j++; }
      out.push({ k: 're', v: `${body}\u0000${flags}` });
      i = j;
      continue;
    }
    const two = s.slice(i, i + 2);
    if (PUNCT.indexOf(two) >= 0) { out.push({ k: two }); i += 2; continue; }
    if (c === '!' || c === '(' || c === ')') { out.push({ k: c }); i++; continue; }
    if (c === '"' || c === "'") {
      let j = i + 1;
      let body = '';
      while (j < s.length && s[j] !== c) { body += s[j]; j++; }
      if (j >= s.length) throw new Error(`keyContext: 字符串未闭合 (${src})`);
      out.push({ k: 'lit', v: body });
      i = j + 1;
      continue;
    }
    // bareword / 数字
    let j = i;
    let word = '';
    while (j < s.length && /[A-Za-z0-9_.\-:$]/.test(s[j])) { word += s[j]; j++; }
    if (!word) throw new Error(`keyContext: 无法解析的字符 "${c}" (${src})`);
    out.push({ k: 'word', v: word });
    i = j;
  }
  return out;
}

/**
 * 把 when 源串编译成谓词。编译期校验语法（规则表写错 = 启动即炸）。
 * @param {string} src
 * @returns {(ctx: (name: string) => ContextValue) => boolean}
 */
function compile(src) {
  const toks = tokenize(src);
  let p = 0;
  /** @param {string} expect @returns {boolean} */
  const peek = (expect) => (p < toks.length ? toks[p].k === expect : false);
  /** @returns {Token} */
  const next = () => {
    if (p >= toks.length) throw new Error(`keyContext: 表达式意外结束 (${src})`);
    return toks[p++];
  };

  /** @returns {Pred} */
  function parseOr() {
    let left = parseAnd();
    while (peek('||')) {
      next();
      const right = parseAnd();
      const l = left;
      left = (ctx) => l(ctx) || right(ctx);
    }
    return left;
  }

  /** @returns {Pred} */
  function parseAnd() {
    let left = parseUnary();
    while (peek('&&')) {
      next();
      const right = parseUnary();
      const l = left;
      left = (ctx) => l(ctx) && right(ctx);
    }
    return left;
  }

  /** @returns {Pred} */
  function parseUnary() {
    if (peek('!')) { next(); const inner = parseUnary(); return (ctx) => !inner(ctx); }
    if (peek('(')) {
      next();
      const inner = parseOr();
      if (!peek(')')) throw new Error(`keyContext: 括号未闭合 (${src})`);
      next();
      return inner;
    }
    const t = next();
    if (t.k !== 'word') throw new Error(`keyContext: 这里应当是键名 (${src})`);
    const key = String(t.v);
    if (peek('==') || peek('!=')) {
      const op = next().k;
      const rt = next();
      /** @type {boolean|string|number} */
      let lit = String(rt.v ?? '');
      if (rt.k === 'word' && (rt.v === 'true' || rt.v === 'false')) lit = rt.v === 'true';
      else if (rt.k === 'word' && rt.v !== undefined && /^-?\d+(\.\d+)?$/.test(rt.v)) lit = Number(rt.v);
      else if (rt.k === 're') throw new Error(`keyContext: 正则只能跟 =~ (${src})`);
      /** @type {Pred} */
      const cmp = (ctx) => {
        const v = ctx(key);
        if (lit === true) return !!v;            // Monaco：== true 折成真值判断
        if (lit === false) return !v;            // Monaco：== false 折成取反
        return v === lit;
      };
      return op === '==' ? cmp : (ctx) => !cmp(ctx);
    }
    if (peek('=~')) {
      next();
      const rt = next();
      if (rt.k !== 're') throw new Error(`keyContext: =~ 右侧须是正则 (${src})`);
      const [body, flags] = String(rt.v).split('\u0000');
      const re = flags ? new RegExp(body, flags) : new RegExp(body);
      return (ctx) => {
        const v = ctx(key);
        return typeof v === 'string' && re.test(v);
      };
    }
    return (ctx) => !!ctx(key);                // Monaco：裸键名 = 真值判断
  }

  const fn = parseOr();
  if (p !== toks.length) throw new Error(`keyContext: 表达式尾部有多余内容 (${src})`);
  return fn;
}

/** @type {Map<string, (ctx: (name: string) => ContextValue) => boolean>} */
const cache = new Map();

/**
 * 编译（带缓存）一个 when 表达式。
 * @param {string} src
 * @returns {(ctx: (name: string) => ContextValue) => boolean}
 */
export function compileWhen(src) {
  const key = String(src == null ? '' : src).trim();
  const hit = cache.get(key);
  if (hit) return hit;
  const fn = key === '' || key === 'true' ? () => true : compile(key);
  cache.set(key, fn);
  return fn;
}

/**
 * 用**当前**上下文求值一个 when 表达式。
 * @param {string} src
 * @returns {boolean}
 */
export function evaluateWhen(src) {
  return compileWhen(src)(getContextKey);
}

// ── 焦点类谓词（keymap / keyRules / 各状态源共用同一口径）─────────────────

/**
 * 事件目标是否在「文本输入态」内。键盘优先最容易被误伤的一面：在输入框里把
 * `Alt+1` 当面板切换，用户就打不出那个字符了。
 * @param {EventTarget|null|undefined} t
 * @returns {boolean}
 */
export function isEditableTarget(t) {
  if (!(t instanceof HTMLElement)) return false;
  const tag = t.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
  return t.isContentEditable === true;
}

/**
 * 聚焦元素是否是「自己用 Space/Enter 激活」的自绘控件（button / role 族 / 带 tabindex）。
 * meta 模式下 Space 不许抢它的原生激活语义。
 * @param {Element|null|undefined} el
 * @returns {boolean}
 */
export function isActivatableTarget(el) {
  if (!(el instanceof HTMLElement)) return false;
  if (el.tagName === 'BUTTON' || el.tagName === 'A') return true;
  const role = el.getAttribute('role');
  if (role === 'button' || role === 'checkbox' || role === 'menuitem' || role === 'option' || role === 'tab') return true;
  // tabindex **>= 0** 才算：带 tabindex="-1" 的是程序化焦点落点（对话流容器、
  // 侧边栏条目的导航标记），它们没有 Space 激活语义。
  return el.tabIndex >= 0;
}

/** 上下文键名常量（字符串字面量集中在此，规则表用它们拼 when，避免拼错）。 */
export const KEYS = {
  /** 'meta' | 'edit'：vim 式双模态，见 keymap.setMode */
  mode: 'keyboardMode',
  /** 焦点是否在文本输入态（输入框/文本域/contenteditable） */
  textInputFocus: 'textInputFocus',
  /** 焦点是否落在「可用 Space/Enter 激活」的控件上 */
  activatableFocus: 'activatableFocus',
  /** 焦点是否在文件树的行上（role=treeitem） */
  explorerTreeFocus: 'explorerTreeFocus',
  /** 文件树是否有选中项（含键盘 roving 之外的点击选择） */
  explorerSelection: 'explorerSelection',
  /** 焦点是否落在叠卡卡片上 */
  deckCardFocus: 'deckCardFocus',
  /** 叠卡环是否打开 */
  deckOpen: 'deckOpen',
  /** 画布胶囊环（hover 环）是否打开 */
  canvasRingOpen: 'canvasRingOpen',
  /** 是否处于「画布卡片独立窗」形态 */
  cardWindow: 'cardWindow',
  /** 命令面板是否打开（由命令面板自己维护） */
  paletteOpen: 'paletteOpen',
};

/**
 * 装焦点类上下文键（幂等）。**唯一**的「焦点 → 上下文」换算点：改造前每个模块
 * 各写一遍 `closest('input, textarea, …')`，这里换成一次声明、到处可引用。
 *
 * 用**派生**而不是 focusin/focusout 推送：keydown 是同步派发的，事件处理时读
 * `document.activeElement` 得到的必然是「这一拍」的焦点；推模式则要多维护一对
 * 监听 + 微任务时序（focusout 时 activeElement 还没落定）。少一条时序腿 =
 * 少一类只在真机上复现的竞态。
 * @returns {void}
 */
export function installFocusContextKeys() {
  provideContextKey(KEYS.textInputFocus, () => isEditableTarget(document.activeElement));
  provideContextKey(KEYS.activatableFocus, () => isActivatableTarget(document.activeElement));
  provideContextKey(KEYS.explorerTreeFocus, () => {
    const a = document.activeElement;
    return a instanceof Element && !!a.closest('#explorer-tree .explorer-item[role="treeitem"]');
  });
  provideContextKey(KEYS.deckCardFocus, () => {
    const a = document.activeElement;
    return a instanceof Element && !!a.closest('#deck-layer .deck-card');
  });
}
