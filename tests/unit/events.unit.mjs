// tests/unit/events.unit.mjs — js/events.js 单测（ask-paradigm-a 批 2026-11-03）
//
// 口径：node:test + node:assert/strict，零浏览器依赖（CustomEvent 用最小桩）。
// 覆盖：EVENTS 无重复值 / 六个热点事件值逐字一致 / 表冻结 / emit 认常量名也认
// 现成事件名 / bubbles 恒 true / 源码零字面量实参（R3）。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

globalThis.CustomEvent = class CustomEvent {
  constructor(type, init = {}) { this.type = type; this.detail = init.detail; this.bubbles = Boolean(init.bubbles); }
};

const { EVENTS, emit } = await import('../../src/main/resources/web/js/events.js');
const SRC = readFileSync(fileURLToPath(new URL('../../src/main/resources/web/js/events.js', import.meta.url)), 'utf8');

/** 最小派发目标。 */
const targetStub = () => ({ last: null, dispatchEvent(ev) { this.last = ev; return true; } });

// ── 注册表 ─────────────────────────────────────────────────────────────
test('EVENTS: 表冻结，值全是非空字符串', () => {
  assert.equal(Object.isFrozen(EVENTS), true);
  const values = Object.values(EVENTS);
  assert.ok(values.length >= 6);
  for (const v of values) {
    assert.equal(typeof v, 'string');
    assert.ok(v.length > 0);
  }
});

test('EVENTS: 无重复值（单源的前提）', () => {
  const values = Object.values(EVENTS);
  assert.equal(new Set(values).size, values.length);
});

test('EVENTS: 六个热点事件值与本仓现存字面量逐字一致', () => {
  assert.equal(EVENTS.WORKSPACE_OPEN_ITEM, 'workspace-open-item');
  assert.equal(EVENTS.FM_GROUPS_CHANGED, 'fm-groups-changed');
  assert.equal(EVENTS.FM_FRIENDS_CHANGED, 'fm-friends-changed');
  assert.equal(EVENTS.SESSION_ATTENTION, 'session-attention');
  assert.equal(EVENTS.LOCALE_CHANGED, 'locale-changed');
  assert.equal(EVENTS.NEBFLOW_TASK_COLLAPSED, 'nebflow-task-collapsed');
});

test('EVENTS: 常量名本身不含空格/小写混拼事故', () => {
  for (const k of Object.keys(EVENTS)) assert.equal(/^[A-Z][A-Z0-9_]*$/.test(k), true, `常量名形态：${k}`);
});

// ── emit ───────────────────────────────────────────────────────────────
test('emit: 传常量名 ⇒ 派发真实事件名，detail 透传，bubbles 恒 true', () => {
  const t = targetStub();
  const detail = { sessionId: 's1', attention: false };
  assert.equal(emit(t, 'SESSION_ATTENTION', detail), true);
  assert.equal(t.last.type, 'session-attention');
  assert.equal(t.last.bubbles, true);
  assert.deepEqual(t.last.detail, detail);
});

test('emit: 传现成事件名 ⇒ 原样透传（兼容老调用点）', () => {
  const t = targetStub();
  emit(t, 'workspace-open-item', { item: 1 });
  assert.equal(t.last.type, 'workspace-open-item');
  assert.deepEqual(t.last.detail, { item: 1 });
});

test('emit: 未知名字原样派发（不静默改名、不抛错）', () => {
  const t = targetStub();
  emit(t, 'probe-unknown-event');
  assert.equal(t.last.type, 'probe-unknown-event');
  assert.equal(t.last.detail, undefined);
});

test('emit: 六个常量名都能解析成对应事件名', () => {
  for (const key of Object.keys(EVENTS)) {
    const t = targetStub();
    emit(t, key, 0);
    assert.equal(t.last.type, EVENTS[key], `${key} 未解析`);
  }
});

// ── 契约（源码级）──────────────────────────────────────────────────────
test('events.js 源文件：CustomEvent 构造里没有字符串字面量实参（R3）', () => {
  assert.equal(/new\s+CustomEvent\s*\(\s*['"`]/.test(SRC), false, '事件名必须走 EVENTS，实参不许是字面量');
});
