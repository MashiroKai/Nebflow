// ── component.js — vanilla 自定义元素基座（ask-paradigm-a 批 2026-11-03）──────
// 为什么有这个文件：Ask 面要变成 <nf-ask-dock> 这样的真组件（light DOM，对外只
// 暴露 open/close/syncSession 方法面），而本仓此前没有组件基座 —— 每个面各自拼
// DOM、自己挂监听、退役时漏拆，组件化没有公共起点。
// 本文件只做一件事：把 {tag, template, state, mount, unmount} 声明注册成一个自定义
// 元素，生命周期口径固定（下游照此写，别改口径）：
//   · light DOM：不建阴影根（门禁 scripts/check-component-contract.mjs R1）。
//     样式单点在本仓的 css/ 层，开阴影根 = 样式与调试双双分叉。
//   · connectedCallback：清空自身 → template(state) 的返回值 appendChild 落 DOM →
//     调 mount(el, ctx)；ctx.on 注册的监听在断开时自动拆。
//   · disconnectedCallback：拆 ctx.on 登记的监听 → 清空自身 → 调 unmount(el)。
//   · 落 DOM 只有 appendChild 一条路径，全程零 HTML 串（门禁 R5）；文本走
//     textContent、属性走 setAttribute（构造器见 ./dom.js 的 h / frag）。
//   · tag 必须 'nf-' 前缀；同名已注册则直接返回（幂等：热重载 / 重复 import 不炸）。

/**
 * mount(el, ctx) 的第二个实参 —— 实例上下文。
 * @typedef {Object} ComponentCtx
 * @property {any} state 本实例的 state（define 的 state 是函数 ⇒ 每实例调用一次）
 * @property {(target: EventTarget, type: string, handler: (ev: any) => void, options?: boolean | AddEventListenerOptions) => void} on
 *   注册监听并登记，断开时自动 removeEventListener（组件作者不必自己记账）
 */

/** 实例上下文（WeakMap 而非元素属性：不污染 DOM 对象的属性面）。 */
/** @type {WeakMap<HTMLElement, {ctx: ComponentCtx, offs: (() => void)[]}>} */
const INSTANCES = new WeakMap();

/**
 * 清空子节点（唯一清空路径：逐个 removeChild）。
 * @param {HTMLElement} el
 * @returns {void}
 */
function clear(el) {
  let child = el.firstChild;
  while (child) { el.removeChild(child); child = el.firstChild; }
}

/**
 * 注册一个 nf-* 自定义元素（light DOM）。重复注册同名 tag 无副作用。
 * @param {{
 *   tag: string,
 *   template: (state: any) => Node|null,
 *   state?: any | (() => any),
 *   mount?: (el: HTMLElement, ctx: ComponentCtx) => void,
 *   unmount?: (el: HTMLElement) => void,
 * }} opts
 * @returns {void}
 */
export function define({ tag, template, state, mount, unmount }) {
  if (typeof tag !== 'string' || !tag.startsWith('nf-')) {
    throw new Error(`component.define: tag 必须以 'nf-' 开头（收到 ${String(tag)}）——本仓自定义元素统一 nf- 命名空间`);
  }
  if (typeof template !== 'function') {
    throw new Error(`component.define: ${tag} 缺少 template(state) —— 基座只接受 template 返回 DOM 节点`);
  }
  if (customElements.get(tag)) return;   // 幂等：已注册过（热重载 / 重复 import）

  class NfComponent extends HTMLElement {
    connectedCallback() {
      /** @type {(() => void)[]} */
      const offs = [];
      /** @type {ComponentCtx} */
      const ctx = {
        state: typeof state === 'function' ? state() : state,
        on(target, type, handler, options) {
          target.addEventListener(type, handler, options);
          offs.push(() => target.removeEventListener(type, handler, options));
        },
      };
      INSTANCES.set(this, { ctx, offs });
      clear(this);
      const node = template(ctx.state);
      if (node) this.appendChild(node);
      if (typeof mount === 'function') mount(this, ctx);
    }

    disconnectedCallback() {
      const inst = INSTANCES.get(this);
      if (!inst) return;
      INSTANCES.delete(this);
      for (const off of inst.offs) { try { off(); } catch { /* 拆监听失败不阻断退役 */ } }
      clear(this);
      if (typeof unmount === 'function') unmount(this);
    }
  }

  customElements.define(tag, NfComponent);
}
