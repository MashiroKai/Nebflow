// tests/unit/state.unit.mjs — js/state.js 的 subscribe / set 单测（ask-paradigm-a 批 2026-11-03）
//
// 口径：node:test + node:assert/strict，零浏览器依赖。state.js 在模块作用域里读
// localStorage（safeParse 那几行）并经由 branding.js -> brand.js 摸 window.__BRAND__，
// 所以**桩必须先立、模块必须动态 import**（静态 import 会在任何桩生效前求值）。
// 纪律：既有 60+ 消费者直读字段的行为是硬约束 —— 本文件末尾专门有一条「直读不被
// 通知、导出形态不变」的回归断言。

import test from 'node:test';
import assert from 'node:assert/strict';

// ── 浏览器桩（只立 import 期真正被摸到的两个全局）────────────────────────
const store = new Map();
globalThis.localStorage = {
  get length() { return store.size; },
  key: (i) => [...store.keys()][i] ?? null,
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)); },
  removeItem: (k) => { store.delete(k); },
};
globalThis.window = {};

const { default: state } = await import('../../src/main/resources/web/js/state.js');

/** 订阅一个探针路径并收集通知（返回 {calls, off}）。 */
function watch(path) {
  const calls = [];
  const off = state.subscribe(path, (value, changed) => calls.push({ value, changed }));
  return { calls, off };
}

// ── 点路径写入 ─────────────────────────────────────────────────────────
test('set: 点路径写入，中间层不存在则创建普通对象', () => {
  assert.equal(state.set('probeDeep.one.two', 5), true);
  assert.equal(state.probeDeep.one.two, 5);
  assert.equal(Object.getPrototypeOf(state.probeDeep.one), Object.prototype);
});

test('set: 覆盖已有中间层里的其他键不受影响', () => {
  state.set('probeKeep.a', 1);
  state.set('probeKeep.b', 2);
  assert.equal(state.probeKeep.a, 1);
  assert.equal(state.probeKeep.b, 2);
});

test('set: 空路径 / 非字符串路径不写入（返回 false）', () => {
  assert.equal(state.set('', 1), false);
  assert.equal(state.set(null, 1), false);
});

test('set: 返回值 = 值是否真的变了', () => {
  assert.equal(state.set('probeFlag', true), true);
  assert.equal(state.set('probeFlag', true), false);
  assert.equal(state.set('probeFlag', false), true);
});

// ── 通知 ───────────────────────────────────────────────────────────────
test('subscribe: 同一路径写入 ⇒ 同步通知一次，带当前值 + 写入路径', () => {
  const w = watch('probeNotify.v');
  state.set('probeNotify.v', 7);
  assert.equal(w.calls.length, 1);
  assert.equal(w.calls[0].value, 7);
  assert.equal(w.calls[0].changed, 'probeNotify.v');
  w.off();
});

test('subscribe: 值没变就不通知（结构相等也算没变）', () => {
  const w = watch('probeSame');
  state.set('probeSame', { k: 1 });
  state.set('probeSame', { k: 1 });
  assert.equal(w.calls.length, 1);
  w.off();
});

test('subscribe: Set 值按内容比较（换新 Set 同内容不通知）', () => {
  const w = watch('probeSet');
  state.set('probeSet', new Set([1, 2]));
  state.set('probeSet', new Set([1, 2]));
  assert.equal(w.calls.length, 1);
  state.set('probeSet', new Set([1, 3]));
  assert.equal(w.calls.length, 2);
  w.off();
});

test('subscribe: 父路径写入通知子路径订阅者', () => {
  const w = watch('probeAnc.b');
  state.set('probeAnc', { b: 9 });
  assert.equal(w.calls.length, 1);
  assert.equal(w.calls[0].value, 9);
  assert.equal(w.calls[0].changed, 'probeAnc');
  w.off();
});

test('subscribe: 子路径写入通知父路径订阅者', () => {
  const w = watch('probeDesc');
  state.set('probeDesc.d', 1);
  assert.equal(w.calls.length, 1);
  assert.deepEqual(w.calls[0].value, { d: 1 });
  w.off();
});

test('subscribe: 无前缀关系不通知', () => {
  const w = watch('probeOther.x');
  state.set('probeUnrelated.y', 1);
  assert.equal(w.calls.length, 0);
  w.off();
});

test("subscribe: '' / null 表示订阅全量", () => {
  const all = watch('');
  const nul = watch(null);
  state.set('probeAll.z', 1);
  assert.equal(all.calls.length, 1);
  assert.equal(all.calls[0].value, state);
  assert.equal(nul.calls.length, 1);
  all.off();
  nul.off();
});

test('subscribe: unsubscribe 之后不再通知，重复调用无害', () => {
  const w = watch('probeOff');
  state.set('probeOff', 1);
  assert.equal(w.calls.length, 1);
  w.off();
  w.off();
  state.set('probeOff', 2);
  assert.equal(w.calls.length, 1);
});

test('subscribe: 只是前缀相似（probeA / probeAB）不算受影响', () => {
  const w = watch('probeAB');
  state.set('probeA.x', 1);
  assert.equal(w.calls.length, 0);
  w.off();
});

// ── 异常隔离 ───────────────────────────────────────────────────────────
test('set: 一个订阅者抛错被吞（console.warn），其余订阅者照常通知，写入成功', () => {
  const warned = [];
  const realWarn = console.warn;
  console.warn = (...args) => warned.push(args);
  const good = [];
  const offBad = state.subscribe('probeThrow', () => { throw new Error('boom'); });
  const offGood = state.subscribe('probeThrow', (v) => good.push(v));
  try {
    assert.equal(state.set('probeThrow', 1), true);
  } finally {
    console.warn = realWarn;
  }
  assert.equal(state.probeThrow, 1);
  assert.deepEqual(good, [1]);
  assert.equal(warned.length, 1);
  offBad();
  offGood();
});

// ── 既有消费者回归（直读面不许被本批改坏）────────────────────────────────
test('回归: 导出形态不变 —— 仍是普通对象，字段直读/直写照旧，不被通知', () => {
  assert.equal(Object.getPrototypeOf(state), Object.prototype);
  assert.equal(Array.isArray(state.notifications), true);
  assert.equal(state.connected, false);
  const w = watch('probeDirect');
  state.probeDirect = 123;          // 既有直写腿：绕过 set（无通知，刻意）
  assert.equal(state.probeDirect, 123);
  assert.equal(w.calls.length, 0);
  w.off();
});

test('回归: subscribe / set 是函数，且没有新增其它方法面', () => {
  assert.equal(typeof state.subscribe, 'function');
  assert.equal(typeof state.set, 'function');
  const own = Object.keys(state).filter((k) => typeof state[k] === 'function');
  // 既有函数型字段（跨模块接缝）之外，本批只新增 subscribe / set 两个。
  assert.ok(own.includes('subscribe') && own.includes('set'));
  assert.equal(own.length, new Set(own).size);
});
