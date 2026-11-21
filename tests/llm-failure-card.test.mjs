// LLM 失败卡片（卡片批 2026-11-21）：纯视图模型回归（无 DOM）。
// 运行：node --test tests/llm-failure-card.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildLlmFailureModel, SUMMARY_MAX } from '../src/main/resources/web/js/llmFailureCard.js';

test('视图模型：标题带代理名，分类映射为中文标签', () => {
  const m = buildLlmFailureModel({ agentName: 'main', summary: 'boom', failureClass: 'llm-overloaded' });
  assert.equal(m.question, '模型调用失败：main');
  assert.equal(m.failureLabel, '模型过载');
  assert.equal(m.waitLabel, '等待恢复');
  assert.equal(m.switchLabel, '换模型');
});

test('视图模型：未知分类原样显示，缺失分类为「未分类」', () => {
  assert.equal(buildLlmFailureModel({ agentName: 'a', failureClass: 'weird-x' }).failureLabel, 'weird-x');
  assert.equal(buildLlmFailureModel({ agentName: 'a' }).failureLabel, '未分类');
});

test('视图模型：摘要折叠空白并截断到上限（含省略号）', () => {
  const long = 'x'.repeat(SUMMARY_MAX + 50);
  const m = buildLlmFailureModel({ agentName: 'a', summary: `  a\n\n b  ${long}` });
  assert.equal(m.summary.length, SUMMARY_MAX);
  assert.ok(m.summary.endsWith('…'));
  assert.ok(!m.summary.includes('\n'));
});

test('视图模型：空摘要给占位文案，不出现 undefined', () => {
  const m = buildLlmFailureModel({ agentName: 'a' });
  assert.equal(m.summary, '（无错误摘要）');
  assert.ok(!JSON.stringify(m).includes('undefined'));
});
