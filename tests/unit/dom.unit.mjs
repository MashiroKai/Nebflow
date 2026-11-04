// tests/unit/dom.unit.mjs — js/dom.js 单测（ask-paradigm-a 批 2026-11-03）
//
// 口径：node:test + node:assert/strict，**零浏览器依赖**（不引 jsdom）。DOM 面用下面
// 这套最小桩：只实现 h()/frag() 真正用到的那几个成员（nodeType / appendChild /
// removeChild / firstChild / setAttribute / style.setProperty / addEventListener）。
// 文件名必须 *.unit.mjs —— playwright.config.mjs 的 testDir 是 tests 且默认
// testMatch 会抓 spec / test 后缀，抓进去会炸整轮收集。
//
// 跑法（唯一跑法，列全四个文件或走通配）：
//   node --test "tests/unit/**/*.unit.mjs"
// 裸目录形态 `node --test tests/unit/` 在 node 23 上无效：node 的默认抓取模式只认
// test/spec 后缀，目录里零匹配时它把目录当入口脚本加载 ⇒ MODULE_NOT_FOUND。
// 文件名被钉成 *.unit.mjs 是为了躲 playwright，所以必须自己点名文件。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// ── 最小 DOM 桩 ────────────────────────────────────────────────────────
class StubNode {
  constructor(nodeType) { this.nodeType = nodeType; this.childNodes = []; this.parentNode = null; }
  appendChild(child) { this.childNodes.push(child); child.parentNode = this; return child; }
  removeChild(child) { const i = this.childNodes.indexOf(child); if (i >= 0) this.childNodes.splice(i, 1); child.parentNode = null; return child; }
  get firstChild() { return this.childNodes.length > 0 ? this.childNodes[0] : null; }
}
class StubStyle {
  constructor() { this.props = new Map(); }
  setProperty(name, value) { this.props.set(name, String(value)); }
  getPropertyValue(name) { return this.props.has(name) ? this.props.get(name) : ''; }
}
class StubElement extends StubNode {
  constructor(tag) {
    super(1);
    this.tagName = String(tag).toUpperCase();
    this.attributes = new Map();
    this.style = new StubStyle();
    this.listeners = new Map();
    this.textContent = '';
  }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  getAttribute(name) { return this.attributes.has(name) ? this.attributes.get(name) : null; }
  addEventListener(type, fn) { if (!this.listeners.has(type)) this.listeners.set(type, []); this.listeners.get(type).push(fn); }
  removeEventListener(type, fn) { const arr = this.listeners.get(type) || []; const i = arr.indexOf(fn); if (i >= 0) arr.splice(i, 1); }
  fire(type) { for (const fn of [...(this.listeners.get(type) || [])]) fn({ type }); }
}
class StubFragment extends StubNode { constructor() { super(11); } }

globalThis.document = {
  createElement: (tag) => new StubElement(tag),
  createTextNode: (text) => ({ nodeType: 3, textContent: String(text) }),
  createDocumentFragment: () => new StubFragment(),
};

const { h, frag } = await import('../../src/main/resources/web/js/dom.js');
const SRC = readFileSync(fileURLToPath(new URL('../../src/main/resources/web/js/dom.js', import.meta.url)), 'utf8');

// ── h(): attrs ─────────────────────────────────────────────────────────
test('h: class 与 className 等价，都落 class 属性', () => {
  assert.equal(h('div', { class: 'a b' }).getAttribute('class'), 'a b');
  assert.equal(h('div', { className: 'c' }).getAttribute('class'), 'c');
});

test('h: style 对象按 setProperty 落盘（camelCase 归一为 dashed，--x 原样）', () => {
  const el = h('div', { style: { color: 'red', backgroundColor: 'blue', '--probe': '1px' } });
  assert.equal(el.style.getPropertyValue('color'), 'red');
  assert.equal(el.style.getPropertyValue('background-color'), 'blue');
  assert.equal(el.style.getPropertyValue('--probe'), '1px');
});

test('h: style 字符串原样写 style 属性', () => {
  assert.equal(h('div', { style: 'color: red' }).getAttribute('style'), 'color: red');
});

test('h: data-* / aria-* / 普通属性走 setAttribute；true ⇒ 空串', () => {
  const el = h('div', { 'data-probe': '1', 'aria-label': 'lbl', title: 't', hidden: true });
  assert.equal(el.getAttribute('data-probe'), '1');
  assert.equal(el.getAttribute('aria-label'), 'lbl');
  assert.equal(el.getAttribute('title'), 't');
  assert.equal(el.getAttribute('hidden'), '');
});

test('h: null / undefined / false 的 attr 整键跳过', () => {
  const el = h('div', { a: null, b: undefined, c: false });
  assert.equal(el.getAttribute('a'), null);
  assert.equal(el.getAttribute('b'), null);
  assert.equal(el.getAttribute('c'), null);
});

test('h: text / textContent 快捷键', () => {
  assert.equal(h('span', { text: 'hi' }).textContent, 'hi');
  assert.equal(h('span', { textContent: 'yo' }).textContent, 'yo');
});

test('h: on:<事件名> 注册监听（唯一事件写法）', () => {
  let hits = 0;
  const btn = h('button', { 'on:click': () => { hits += 1; } });
  assert.equal(btn.listeners.get('click').length, 1);
  btn.fire('click');
  btn.fire('click');
  assert.equal(hits, 2);
});

test('h: 只认 on:<事件名>，onclick 不会被当事件注册（会落成属性）', () => {
  const btn = h('button', { onclick: 'nope' });
  assert.equal(btn.listeners.size, 0);
  assert.equal(btn.getAttribute('onclick'), 'nope');
});

// ── h(): children ──────────────────────────────────────────────────────
test('h: children 支持字符串 / 节点 / 数组 / 嵌套数组', () => {
  const node = h('b', { text: 'n' });
  const el = h('div', null, ['a', node, ['b', [node]]]);
  assert.equal(el.childNodes.length, 4);
  assert.equal(el.childNodes[0].nodeType, 3);
  assert.equal(el.childNodes[0].textContent, 'a');
  assert.equal(el.childNodes[1], node);
  assert.equal(el.childNodes[2].textContent, 'b');
  assert.equal(el.childNodes[3], node);
});

test('h: children 的 null / undefined / false 跳过；0 与空串保留', () => {
  const el = h('div', null, [null, undefined, false, 0, '', h('i')]);
  assert.equal(el.childNodes.length, 3);
});

test('h: 无 attrs 无 children 也合法', () => {
  const el = h('div');
  assert.equal(el.tagName, 'DIV');
  assert.equal(el.childNodes.length, 0);
});

test('h: 嵌套构造（h 套 h）只走 appendChild', () => {
  const el = h('ul', null, h('li', { text: 'x' }));
  assert.equal(el.childNodes.length, 1);
  assert.equal(el.childNodes[0].tagName, 'LI');
});

// ── frag() ─────────────────────────────────────────────────────────────
test('frag: 变参 form 与单数组 form 等价', () => {
  const a = frag('x', h('i'), ['y']);
  const b = frag(['x', h('i'), ['y']]);
  assert.equal(a.nodeType, 11);
  assert.equal(a.childNodes.length, 3);
  assert.equal(b.childNodes.length, 3);
  assert.equal(a.childNodes[0].textContent, 'x');
});

test('frag: 跳过 null / undefined / false', () => {
  assert.equal(frag(null, undefined, false).childNodes.length, 0);
});

// ── 契约（源码级）──────────────────────────────────────────────────────
test('dom.js 源文件：零 HTML 串赋值（R5）、不建阴影根（R1）', () => {
  assert.equal(/\binnerHTML\s*=/.test(SRC), false, 'dom.js 不许出现 HTML 串赋值');
  assert.equal(/attachShadow/.test(SRC), false, 'dom.js 不许建阴影根');
});
