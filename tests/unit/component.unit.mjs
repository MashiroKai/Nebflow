// tests/unit/component.unit.mjs — js/component.js 单测（ask-paradigm-a 批 2026-11-03）
//
// 口径：node:test + node:assert/strict，零浏览器依赖 —— 自定义元素的两个全局
// （HTMLElement / customElements）用下面这套最小桩。注意时序：component.js 里的
// `class ... extends HTMLElement` 在 define() **调用时**才求值，所以桩只要在调
// define() 之前立好即可，静态 import 不会踩空。
// 覆盖：tag 校验 / template 校验 / 幂等注册 / 生命周期（渲染→mount→断开→unmount）/
// ctx.on 自动拆监听 / state 工厂每实例一次 / 零 HTML 串与不建阴影根（源码级）。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// ── 最小自定义元素桩 ───────────────────────────────────────────────────
class StubHTMLElement {
  constructor() { this.childNodes = []; this.parentNode = null; this.listeners = new Map(); this.tagName = ''; }
  appendChild(child) { this.childNodes.push(child); child.parentNode = this; return child; }
  removeChild(child) { const i = this.childNodes.indexOf(child); if (i >= 0) this.childNodes.splice(i, 1); child.parentNode = null; return child; }
  get firstChild() { return this.childNodes.length > 0 ? this.childNodes[0] : null; }
  addEventListener(type, fn) { if (!this.listeners.has(type)) this.listeners.set(type, []); this.listeners.get(type).push(fn); }
  removeEventListener(type, fn) { const arr = this.listeners.get(type) || []; const i = arr.indexOf(fn); if (i >= 0) arr.splice(i, 1); }
  listenerCount(type) { return (this.listeners.get(type) || []).length; }
}

const registry = new Map();
const defineCalls = [];
globalThis.HTMLElement = StubHTMLElement;
globalThis.customElements = {
  get: (tag) => registry.get(tag),
  define: (tag, ctor) => {
    if (registry.has(tag)) throw new Error(`stub: ${tag} 重复注册`);
    registry.set(tag, ctor);
    defineCalls.push(tag);
  },
};

const { define } = await import('../../src/main/resources/web/js/component.js');
const SRC = readFileSync(fileURLToPath(new URL('../../src/main/resources/web/js/component.js', import.meta.url)), 'utf8');

/** 造一个「返回给定节点的 template」。 */
const nodeStub = (label) => ({ nodeType: 1, label, parentNode: null });
const templateOf = (label = 'n') => (() => nodeStub(label));

// ── 校验 ───────────────────────────────────────────────────────────────
test('define: tag 非 nf- 前缀直接 throw', () => {
  assert.throws(() => define({ tag: 'div', template: templateOf() }), /nf-/);
  assert.throws(() => define({ tag: 'x-probe', template: templateOf() }), /nf-/);
  assert.throws(() => define({ tag: 'nf', template: templateOf() }), /nf-/);
  assert.throws(() => define({ tag: '', template: templateOf() }), /nf-/);
  assert.throws(() => define({ tag: null, template: templateOf() }), /nf-/);
});

test('define: 缺 template(state) 直接 throw', () => {
  assert.throws(() => define({ tag: 'nf-no-template' }), /template/);
  assert.throws(() => define({ tag: 'nf-bad-template', template: 'not-a-fn' }), /template/);
});

test('define: 校验失败不产生注册副作用', () => {
  assert.equal(registry.has('div'), false);
  assert.equal(registry.has('nf-no-template'), false);
});

// ── 注册 ───────────────────────────────────────────────────────────────
test('define: 注册自定义元素（light DOM，构造器进 customElements）', () => {
  define({ tag: 'nf-probe-a', template: templateOf() });
  assert.equal(typeof registry.get('nf-probe-a'), 'function');
  assert.equal(defineCalls.filter((t) => t === 'nf-probe-a').length, 1);
});

test('define: 同名重复注册幂等（跳过，不抛错，构造器不变）', () => {
  define({ tag: 'nf-probe-b', template: templateOf('first') });
  const first = registry.get('nf-probe-b');
  define({ tag: 'nf-probe-b', template: templateOf('second') });
  assert.equal(registry.get('nf-probe-b'), first);
  assert.equal(defineCalls.filter((t) => t === 'nf-probe-b').length, 1);
});

// ── 生命周期 ───────────────────────────────────────────────────────────
test('生命周期: 渲染 → mount(el, ctx) → 断开 → 拆监听/清子节点/unmount(el)', () => {
  const seen = { template: 0, mount: [], unmount: [] };
  define({
    tag: 'nf-probe-life',
    state: { n: 1 },
    template(s) { seen.template += 1; const el = nodeStub('rendered'); el.stateSeen = s; return el; },
    mount(el, ctx) {
      seen.mount.push({ el, ctx });
      ctx.on(el, 'click', () => {});
    },
    unmount(el) { seen.unmount.push(el); },
  });

  const Ctor = registry.get('nf-probe-life');
  const el = new Ctor();
  el.connectedCallback();

  assert.equal(seen.template, 1);
  assert.equal(el.childNodes.length, 1);
  assert.equal(el.childNodes[0].label, 'rendered');
  assert.deepEqual(el.childNodes[0].stateSeen, { n: 1 }, 'template 收到 state');
  assert.equal(seen.mount.length, 1);
  assert.equal(seen.mount[0].el, el);
  assert.deepEqual(seen.mount[0].ctx.state, { n: 1 });
  assert.equal(el.listenerCount('click'), 1, 'ctx.on 挂上了监听');

  el.disconnectedCallback();
  assert.equal(el.listenerCount('click'), 0, 'ctx.on 的监听在断开时被拆');
  assert.equal(el.childNodes.length, 0, '断开后清空子节点');
  assert.equal(seen.unmount.length, 1);
  assert.equal(seen.unmount[0], el);
});

test('生命周期: disconnectedCallback 重入安全（unmount 只调一次）', () => {
  let unmounted = 0;
  define({ tag: 'nf-probe-reentry', template: templateOf(), unmount: () => { unmounted += 1; } });
  const el = new (registry.get('nf-probe-reentry'))();
  el.connectedCallback();
  el.disconnectedCallback();
  el.disconnectedCallback();
  assert.equal(unmounted, 1);
});

test('生命周期: 未 mount/unmount 时断开也不炸', () => {
  define({ tag: 'nf-probe-bare', template: templateOf() });
  const el = new (registry.get('nf-probe-bare'))();
  el.connectedCallback();
  assert.equal(el.childNodes.length, 1);
  el.disconnectedCallback();
  assert.equal(el.childNodes.length, 0);
});

test('生命周期: template 返回 null 时只清空不追加（不炸）', () => {
  define({ tag: 'nf-probe-null', template: () => null });
  const el = new (registry.get('nf-probe-null'))();
  el.connectedCallback();
  assert.equal(el.childNodes.length, 0);
});

test('生命周期: state 是函数时每实例各调一次（实例隔离）', () => {
  let calls = 0;
  define({ tag: 'nf-probe-factory', state: () => ({ id: ++calls }), template: (s) => nodeStub(String(s.id)) });
  const Ctor = registry.get('nf-probe-factory');
  const a = new Ctor(); a.connectedCallback();
  const b = new Ctor(); b.connectedCallback();
  assert.equal(calls, 2);
  assert.equal(a.childNodes[0].label, '1');
  assert.equal(b.childNodes[0].label, '2');
});

test('生命周期: 重连时重渲染（先清空再追加，不叠加）', () => {
  define({ tag: 'nf-probe-render-again', template: templateOf('x') });
  const el = new (registry.get('nf-probe-render-again'))();
  el.connectedCallback();
  el.disconnectedCallback();
  el.connectedCallback();
  assert.equal(el.childNodes.length, 1);
});

// ── 契约（源码级）──────────────────────────────────────────────────────
test('component.js 源文件：零 HTML 串赋值（R5）、不建阴影根（R1）', () => {
  assert.equal(/\binnerHTML\s*=/.test(SRC), false, 'component.js 不许出现 HTML 串赋值');
  assert.equal(/attachShadow/.test(SRC), false, 'component.js 不许建阴影根');
});
