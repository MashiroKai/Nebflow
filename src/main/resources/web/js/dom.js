// ── dom.js — light DOM 构建器（ask-paradigm-a 批 2026-11-03）───────────────
// 为什么有这个文件：本仓写 DOM 的历史形态是字符串拼 HTML 串再一次性灌入元素
// （存量 670 处 / 115 文件，见 scripts/component-contract.mjs R5 的基线）。后果是
// 文本要自己转义、事件名与类名没有单点、组件化无处落脚。本文件是 vanilla 组件
// 范式的地基 —— **新代码用它，禁裸 HTML 串拼接**：
//   · 文本一律 textContent；属性一律 setAttribute；事件一律 addEventListener；
//   · 本文件内不存在任何 HTML 串落 DOM 的路径。
// 门禁（任一红 = 契约被回退，不许改基线，改代码）：
//   scripts/check-component-contract.mjs
//     R1 light DOM（禁开阴影根）/ R3 事件名单源 / R4 组件内部类名不外泄
//     R5 禁裸 HTML 串赋值（本条对**新文件**要求计数为 0）
//
// ── API（下游组件按此写，形态不许改）───────────────────────────────────
//   h(tag, attrs?, children?) → HTMLElement
//     attrs 键的处置（顺序即优先级）：
//       · 'class' | 'className'   → class 属性（两写法等价，取其一）
//       · 'style'                 → 字符串 ⇒ style 属性原样；对象 ⇒ 逐键 setProperty
//                                   （'--x' 原样；camelCase 归一为 dashed）
//       · 'textContent' | 'text'  → 文本快捷方式（等价 textContent）
//       · 'on:<事件名>'           → addEventListener('<事件名>', fn)
//                                   **本文件只认 on:<事件名> 一种写法**，不认
//                                   onclick 这类 on* 属性（后者会被当普通属性写到
//                                   HTML 属性上 —— 那正是字符串事件的回退路径）
//       · data-* / aria-* / 其它   → setAttribute（true ⇒ 空串；
//                                   null / undefined / false ⇒ 整键跳过）
//     children：节点 / 字符串 / 数字 / 数组（可任意嵌套）/
//               null·undefined·false·true（跳过，便于 `cond && h(...)` 直写）
//   frag(...children) → DocumentFragment
//     变参形态（frag(a, b, c)）；单个数组实参 frag([a, b]) 等价 —— children 口径同 h。

/**
 * 子节点叶子形态（null/undefined/false/true 跳过）。
 * @typedef {Node|string|number|boolean|null|undefined} ChildLeaf
 */

/**
 * 子节点可接受形态：叶子，或叶子数组（运行期**任意深嵌套**都支持 —— 递归在
 * appendChildren 里，类型只描述一层，深一层靠 any 转换，别把类型写死成递归别名）。
 * @typedef {ChildLeaf|ChildLeaf[]} Child
 */

/**
 * 落内联样式：字符串原样写 style 属性；对象逐键 setProperty。
 * @param {HTMLElement} el
 * @param {string|Record<string, string|number|boolean>|null|undefined} style
 * @returns {void}
 */
function applyStyle(el, style) {
  if (style === null || style === undefined) return;
  if (typeof style === 'string') { el.setAttribute('style', style); return; }
  for (const [prop, value] of Object.entries(style)) {
    if (value === null || value === undefined || value === false) continue;
    const name = prop.startsWith('--') ? prop : prop.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
    el.style.setProperty(name, String(value));
  }
}

/**
 * 递归追加子节点（唯一落 DOM 路径：appendChild / createTextNode）。
 * 形参有意用 any：children 是「叶子 / 数组（任意深嵌套）」的递归形态，写死成
 * 联合类型只会把合法调用方挡在外面（JSDoc 类型别名不能自引用）。
 * @param {Node} parent
 * @param {any} children
 * @returns {void}
 */
function appendChildren(parent, children) {
  if (children === null || children === undefined || children === false || children === true) return;
  if (Array.isArray(children)) { for (const child of children) appendChildren(parent, child); return; }
  // 鸭子判定（nodeType 存在即节点）：不依赖全局 Node 构造器，纯函数可测。
  if (typeof children === 'object' && /** @type {any} */ (children).nodeType !== undefined) {
    parent.appendChild(/** @type {Node} */ (children));
    return;
  }
  parent.appendChild(document.createTextNode(String(children)));
}

/**
 * 创建设置好属性与子节点的元素（light DOM，零 HTML 串）。
 * @template {keyof HTMLElementTagNameMap} K
 * @param {K} tag
 * @param {Record<string, any>|null} [attrs]
 * @param {Child} [children]
 * @returns {HTMLElementTagNameMap[K]}
 */
export function h(tag, attrs, children) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [name, value] of Object.entries(attrs)) {
      if (value === null || value === undefined || value === false) continue;
      if (name === 'class' || name === 'className') { el.setAttribute('class', String(value)); continue; }
      if (name === 'style') { applyStyle(el, value); continue; }
      if (name === 'textContent' || name === 'text') { el.textContent = String(value); continue; }
      if (name.startsWith('on:')) { el.addEventListener(name.slice(3), /** @type {any} */ (value)); continue; }
      el.setAttribute(name, value === true ? '' : String(value));
    }
  }
  appendChildren(el, children);
  return /** @type {HTMLElementTagNameMap[K]} */ (el);
}

/**
 * 建 DocumentFragment（变参；children 口径同 h）。
 * @param {...Child} children
 * @returns {DocumentFragment}
 */
export function frag(...children) {
  const box = document.createDocumentFragment();
  appendChildren(box, children);
  return box;
}

/**
 * 把已净化的 HTML 串（典型 = renderMarkdownWithMath 的产物）落到 node 的子树 ——
 * 组件契约 R5 的合规写法（同 askDialog.js setMarkdownHtml：DOMParser(text/html) →
 * importNode → replaceChildren；零脚本执行，属性/文本/子树与 innerHTML 逐字相同）。
 *
 * 前缀 `<body>`：让解析器直接进入 in-body 模式。否则前导的 `<style>`/`<meta>`/
 * `<link>` 会被吸进 document.head（于是从子树消失），前导空白会被丢弃；加前缀后
 * 它们与 innerHTML（in-body 片段解析）落在同一位置，子树逐字节一致。
 *
 * @param {Element} node 目标节点（其全部子节点被替换）
 * @param {string|null|undefined} html
 */
export function setHtmlTree(node, html) {
  const parsed = new DOMParser().parseFromString('<body>' + (html == null ? '' : String(html)), 'text/html');
  node.replaceChildren(...Array.from(parsed.body.childNodes, (n) => document.importNode(n, true)));
}
