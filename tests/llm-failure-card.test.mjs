// LLM 失败卡片（卡片批 2026-11-21；第 3 轮聚合键改造）：纯视图模型回归（无 DOM）。
// 运行：node --test tests/llm-failure-card.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildLlmFailureModel, llmFailureKey, SUMMARY_MAX, AGENTS_SHOW_MAX,
} from '../src/main/resources/web/js/llmFailureCard.js';

test('视图模型：标题为「模型 调用失败」（大字主信息），分类映射为中文标签', () => {
  const m = buildLlmFailureModel({ layer: 'root', modelRef: 'p/deepseek', agents: ['main'], summary: 'boom', failureClass: 'llm-overloaded' });
  assert.equal(m.title, 'p/deepseek 调用失败');
  assert.equal(m.failureLabel, '过载');
  assert.equal(m.waitLabel, '等待');
  assert.equal(m.switchLabel, '换模型');
});

test('视图模型：层级映射为中文标签与图标；未知层级回落到主代理', () => {
  assert.equal(buildLlmFailureModel({ layer: 'team', modelRef: 'm' }).layerLabel, '团队成员');
  assert.equal(buildLlmFailureModel({ layer: 'subtask', modelRef: 'm' }).layerIcon, 'network');
  assert.equal(buildLlmFailureModel({ layer: 'bogus', modelRef: 'm' }).layer, 'root');
});

test('视图模型：聚合键 = 层级 + 模型；同层级同模型同键，异层级异键', () => {
  assert.equal(llmFailureKey('team', 'p/a'), 'team|p/a');
  assert.equal(buildLlmFailureModel({ layer: 'team', modelRef: 'p/a', agents: ['x'] }).key,
    buildLlmFailureModel({ layer: 'team', modelRef: 'p/a', agents: ['y', 'z'] }).key);
  assert.notEqual(llmFailureKey('root', 'p/a'), llmFailureKey('team', 'p/a'));
});

test('视图模型：代理列表超过上限折叠为「等 N 个」', () => {
  const few = buildLlmFailureModel({ layer: 'team', modelRef: 'm', agents: ['a', 'b'] });
  assert.equal(few.agentsText, 'a、b');
  const many = buildLlmFailureModel({ layer: 'team', modelRef: 'm', agents: ['a', 'b', 'c', 'd', 'e'] });
  assert.equal(AGENTS_SHOW_MAX, 3);
  assert.equal(many.agentsText, 'a、b、c 等 5 个');
  assert.equal(buildLlmFailureModel({ layer: 'root', modelRef: 'm' }).agentsText, '');
});

test('视图模型：未知分类原样显示，缺失分类为「未分类」', () => {
  assert.equal(buildLlmFailureModel({ modelRef: 'm', failureClass: 'weird-x' }).failureLabel, 'weird-x');
  assert.equal(buildLlmFailureModel({ modelRef: 'm' }).failureLabel, '未分类');
});

test('视图模型：摘要折叠空白并截断到上限（含省略号）', () => {
  const long = 'x'.repeat(SUMMARY_MAX + 50);
  const m = buildLlmFailureModel({ modelRef: 'm', summary: `  a\n\n b  ${long}` });
  assert.equal(m.summary.length, SUMMARY_MAX);
  assert.ok(m.summary.endsWith('…'));
  assert.ok(!m.summary.includes('\n'));
});

test('视图模型：空摘要给占位文案，缺模型名给「未知模型」，不出现 undefined', () => {
  const m = buildLlmFailureModel({});
  assert.equal(m.summary, '（无错误摘要）');
  assert.equal(m.title, '未知模型 调用失败');
  assert.ok(!JSON.stringify(m).includes('undefined'));
});

// ── 消失语义（2026-11-22 作者 23:24 报「点了不消失」）──
import {
  isLlmFailureControlTarget, createLlmDismissStore, SWITCH_FADE_MS, FADE_OUT_MS,
} from '../src/main/resources/web/js/llmFailureCard.js';

const fakeEl = (hits) => ({ closest: (sel) => (hits.some((h) => sel.includes(h)) ? {} : null) });

test('消失：控件区（按钮/下拉/关闭钮/动作行）点击不触发正文消失', () => {
  assert.equal(isLlmFailureControlTarget(fakeEl(['button'])), true);
  assert.equal(isLlmFailureControlTarget(fakeEl(['select'])), true);
  assert.equal(isLlmFailureControlTarget(fakeEl(['llm-failure-close'])), true);
  assert.equal(isLlmFailureControlTarget(fakeEl(['llm-failure-actions'])), true);
  assert.equal(isLlmFailureControlTarget(fakeEl([])), false);
  assert.equal(isLlmFailureControlTarget(null), false);
});

test('消失：dismiss store 记录同组，recovered 清除后才允许再出', () => {
  const s = createLlmDismissStore();
  const k = llmFailureKey('root', 'p/a');
  assert.equal(s.has(k), false);
  s.add(k);
  assert.equal(s.has(k), true);
  assert.equal(s.has(llmFailureKey('team', 'p/a')), false, '不同层级互不影响');
  s.clear(k);
  assert.equal(s.has(k), false);
  assert.equal(s.size(), 0);
});

test('消失：切换成功自动淡出的时长常量为正且淡出早于移除', () => {
  assert.ok(SWITCH_FADE_MS > 0);
  assert.ok(FADE_OUT_MS > 0 && FADE_OUT_MS < SWITCH_FADE_MS);
});
